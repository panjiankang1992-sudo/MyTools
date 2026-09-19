package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class AutomationOutboxRelayTest {

    @Test
    void shouldMarkEventOnlyAfterAutomationAcceptsMessageIdentifier() {
        MessagingRepository repository = mock(MessagingRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String claimToken = UUID.randomUUID().toString();
        when(repository.claimUnpublishedInboundEvents(4, Duration.ofSeconds(30)))
                .thenReturn(List.of(new MessagingRepository.OutboxEvent(eventId, messageId, claimToken)),
                        List.of());
        when(repository.markOutboxPublished(eventId, claimToken)).thenReturn(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("http://automation.test/internal/v1/message-events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer automation-token"))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andRespond(withAccepted());
        MessagingProperties properties = new MessagingProperties("http://scheduler", "messaging-token",
                "sender@example.com", "http://automation.test", "automation-token", true, 10, 30, false,
                "http://download.test", "download-token", "http://resolver.test", "resolver-token",
                "http://qq.test", "qq-token", "http://telegram.test", "telegram-token");

        AutomationOutboxRelay relay = new AutomationOutboxRelay(repository, properties, builder);
        relay.relay();
        relay.close();

        verify(repository).markOutboxPublished(eventId, claimToken);
        server.verify();
    }

    @Test
    void shouldBackoffFailedEventAndContinueWithNextMessage() {
        MessagingRepository repository = mock(MessagingRepository.class);
        UUID failedEventId = UUID.randomUUID();
        UUID failedMessageId = UUID.randomUUID();
        UUID acceptedEventId = UUID.randomUUID();
        UUID acceptedMessageId = UUID.randomUUID();
        String claimToken = UUID.randomUUID().toString();
        when(repository.claimUnpublishedInboundEvents(4, Duration.ofSeconds(30))).thenReturn(List.of(
                new MessagingRepository.OutboxEvent(failedEventId, failedMessageId, claimToken),
                new MessagingRepository.OutboxEvent(acceptedEventId, acceptedMessageId, claimToken)), List.of());
        when(repository.markOutboxPublished(acceptedEventId, claimToken)).thenReturn(true);
        when(repository.recordInboundOutboxFailure(
                failedEventId, claimToken, 10, "InternalServerError"))
                .thenReturn(MessagingRepository.InboundOutboxFailureOutcome.RETRY_SCHEDULED);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("http://automation.test/internal/v1/message-events"))
                .andExpect(jsonPath("$.messageId").value(failedMessageId.toString()))
                .andRespond(withServerError());
        server.expect(requestTo("http://automation.test/internal/v1/message-events"))
                .andExpect(jsonPath("$.messageId").value(acceptedMessageId.toString()))
                .andRespond(withAccepted());
        MessagingProperties properties = new MessagingProperties("http://scheduler", "messaging-token",
                "sender@example.com", "http://automation.test", "automation-token", true, 10, 30, false,
                "http://download.test", "download-token", "http://resolver.test", "resolver-token",
                "http://qq.test", "qq-token", "http://telegram.test", "telegram-token");

        AutomationOutboxRelay relay = new AutomationOutboxRelay(repository, properties, builder);
        relay.relay();
        relay.close();

        verify(repository).recordInboundOutboxFailure(
                failedEventId, claimToken, 10, "InternalServerError");
        verify(repository).markOutboxPublished(acceptedEventId, claimToken);
        server.verify();
    }
}
