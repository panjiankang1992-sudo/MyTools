package com.yuyutian.mytools.auth.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationMailDeliveryPayloadCipherTest {

    @Test
    void shouldEncryptWithoutPersistingPlaintextAndDecryptWithBoundIdentity() {
        RegistrationMailDeliveryPayloadCipher cipher = cipher((byte) 7);
        RegistrationMailDeliveryOutbox record = cipher.encrypt(
                71L, "user@example.com", "123456", LocalDateTime.now());

        assertThat(record.encryptedPayload()).doesNotContain("user@example.com", "123456");
        RegistrationMailDeliveryPayload payload = cipher.decrypt(record);
        assertThat(payload.recipient()).isEqualTo("user@example.com");
        assertThat(payload.body()).contains("123456");

        RegistrationMailDeliveryOutbox tampered = new RegistrationMailDeliveryOutbox(
                record.verificationId(), "registration-code:72", record.encryptedPayload(), record.nonce(),
                record.status(), record.attemptCount(), record.availableAt(), record.claimedUntil(),
                record.lastErrorCode(), record.createdAt(), record.updatedAt());
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher((byte) 8).decrypt(record)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailClosedWhenPrimaryKeyIsMissing() {
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode("PRIMARY");

        assertThatThrownBy(() -> new RegistrationMailDeliveryPayloadCipher(properties, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }

    private RegistrationMailDeliveryPayloadCipher cipher(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        RegistrationMailSidecarProperties properties = new RegistrationMailSidecarProperties();
        properties.setMode("PRIMARY");
        properties.setDeliveryEncryptionKey(Base64.getEncoder().encodeToString(key));
        return new RegistrationMailDeliveryPayloadCipher(properties, new ObjectMapper());
    }
}
