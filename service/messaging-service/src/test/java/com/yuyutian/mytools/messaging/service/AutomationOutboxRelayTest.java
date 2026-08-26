package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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

class AutomationOutboxRelayTest {

    @Test
    void shouldMarkEventOnlyAfterAutomationAcceptsMessageIdentifier() {
        MessagingRepository repository = mock(MessagingRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.findUnpublishedInboundEvents(10)).thenReturn(List.of(
                new MessagingRepository.OutboxEvent(eventId, messageId)));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://automation.test/internal/v1/message-events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer automation-token"))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andRespond(withAccepted());
        MessagingProperties properties = new MessagingProperties("http://scheduler", "messaging-token",
                "sender@example.com", "http://automation.test", "automation-token", true, 10, false,
                "http://download.test", "download-token", "http://resolver.test", "resolver-token",
                "http://qq.test", "qq-token", "http://telegram.test", "telegram-token");

        new AutomationOutboxRelay(repository, properties, builder).relay();

        verify(repository).markOutboxPublished(eventId);
        server.verify();
    }
}
