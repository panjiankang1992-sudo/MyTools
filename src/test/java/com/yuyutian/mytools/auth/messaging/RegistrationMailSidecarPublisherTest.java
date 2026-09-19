package com.yuyutian.mytools.auth.messaging;

import com.yuyutian.mytools.auth.mapper.RegistrationMailShadowOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class RegistrationMailSidecarPublisherTest {

    @Test
    void shouldClaimSubmitRedactedShadowAndAcknowledge() {
        RegistrationMailSidecarProperties properties = enabledProperties();
        RegistrationMailShadowOutboxMapper mapper = mock(RegistrationMailShadowOutboxMapper.class);
        RegistrationMailShadowOutbox record = record(91L, 0);
        when(mapper.findReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(record));
        when(mapper.claim(eq(91L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://messaging.test/internal/v1/delivery-shadows"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer internal-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("registration-code:91"))
                .andExpect(jsonPath("$.recipientHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.payloadHash").value("b".repeat(64)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("user@example.com"))))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON));

        new RegistrationMailSidecarPublisher(properties, mapper, builder).relay();

        server.verify();
        verify(mapper).acknowledge(eq(91L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(mapper, never()).fail(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void shouldBackoffAfterRemoteFailure() {
        RegistrationMailSidecarProperties properties = enabledProperties();
        RegistrationMailShadowOutboxMapper mapper = mock(RegistrationMailShadowOutboxMapper.class);
        RegistrationMailShadowOutbox record = record(92L, 2);
        when(mapper.findReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of(record));
        when(mapper.claim(eq(92L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://messaging.test/internal/v1/delivery-shadows"))
                .andRespond(withServerError());

        new RegistrationMailSidecarPublisher(properties, mapper, builder).relay();

        ArgumentCaptor<LocalDateTime> availableAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> failedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).fail(eq(92L), any(LocalDateTime.class), eq("PENDING"), eq(3), availableAt.capture(),
                eq("InternalServerError"), failedAt.capture());
        assertThat(availableAt.getValue()).isAfterOrEqualTo(failedAt.getValue().plusSeconds(20));
        verify(mapper, never()).acknowledge(any(), any(), any());
        server.verify();
    }

    @Test
    void shouldDoNothingWhenSidecarIsDisabled() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        RegistrationMailShadowOutboxMapper mapper = mock(RegistrationMailShadowOutboxMapper.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        new RegistrationMailSidecarPublisher(properties, mapper, builder).relay();

        verify(mapper, never()).findReady(any(), anyInt());
        server.verify();
    }

    @Test
    void shouldRelayAutomaticallyInShadowMode() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode("SHADOW");
        RegistrationMailShadowOutboxMapper mapper = mock(RegistrationMailShadowOutboxMapper.class);
        when(mapper.findReady(any(LocalDateTime.class), eq(50))).thenReturn(List.of());

        new RegistrationMailSidecarPublisher(properties, mapper, RestClient.builder()).relay();

        verify(mapper).findReady(any(LocalDateTime.class), eq(50));
    }

    private RegistrationMailSidecarProperties enabledProperties() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setEnabled(true);
        properties.setServiceUrl("http://messaging.test");
        properties.setInternalToken("internal-token");
        properties.setHashKey("test-shadow-hmac-key-that-is-at-least-32-bytes");
        return properties;
    }

    private RegistrationMailShadowOutbox record(Long id, int attemptCount) {
        LocalDateTime now = LocalDateTime.now();
        return new RegistrationMailShadowOutbox(id, "registration-code:" + id, "a".repeat(64),
                "b".repeat(64), "DELIVERED", "PENDING", attemptCount, now, null, null, now, now);
    }
}
