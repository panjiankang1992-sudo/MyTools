package com.yuyutian.mytools.auth.messaging;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationMailShadowPayloadFactoryTest {

    @Test
    void shouldEnableEvidenceAutomaticallyInShadowMode() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode("SHADOW");

        assertThat(new RegistrationMailShadowPayloadFactory(properties).isEnabled()).isTrue();
    }

    @Test
    void shouldCreateStableRedactedPayload() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setEnabled(true);
        properties.setHashKey("test-shadow-hmac-key-that-is-at-least-32-bytes");
        RegistrationMailShadowPayloadFactory factory = new RegistrationMailShadowPayloadFactory(properties);
        LocalDateTime now = LocalDateTime.now();

        RegistrationMailShadowOutbox first = factory.create(7L, " User@Example.com ", "123456", now);
        RegistrationMailShadowOutbox second = factory.create(8L, "user@example.com", "123456", now);

        assertThat(first.recipientHmac()).hasSize(64).isEqualTo(second.recipientHmac());
        assertThat(first.payloadHmac()).hasSize(64).isEqualTo(second.payloadHmac());
        assertThat(first.toString()).doesNotContain("user@example.com", "123456");
        assertThat(first.idempotencyKey()).isEqualTo("registration-code:7");
    }

    @Test
    void shouldRejectWeakHashKeyWhenCreatingPayload() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setEnabled(true);
        properties.setHashKey("short");
        RegistrationMailShadowPayloadFactory factory = new RegistrationMailShadowPayloadFactory(properties);

        assertThatThrownBy(() -> factory.create(7L, "user@example.com", "123456", LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);
    }
}
