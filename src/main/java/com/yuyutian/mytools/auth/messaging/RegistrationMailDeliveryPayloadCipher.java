package com.yuyutian.mytools.auth.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 注册邮件 Outbox 载荷 AES-GCM 加解密器。
 */
@Component
public class RegistrationMailDeliveryPayloadCipher {

    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final ObjectMapper objectMapper;
    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建载荷加解密器。
     *
     * @param properties 注册邮件迁移配置
     * @param objectMapper JSON 映射器
     */
    public RegistrationMailDeliveryPayloadCipher(RegistrationMailSidecarProperties properties,
                                                  ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        String configuredKey = properties.getDeliveryEncryptionKey().trim();
        if (configuredKey.isEmpty()) {
            this.secretKey = null;
            if (!properties.getMode().equalsIgnoreCase("LEGACY")) {
                throw new IllegalStateException("Registration mail delivery encryption key is required");
            }
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Registration mail delivery encryption key must be Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("Registration mail delivery encryption key must contain 32 bytes");
        }
        this.secretKey = new SecretKeySpec(decoded, "AES");
    }

    /**
     * 创建仅含密文的真实投递 Outbox。
     *
     * @param verificationId 验证码标识
     * @param email 收件邮箱
     * @param code 明文验证码
     * @param now 创建时间
     * @return 加密 Outbox 记录
     */
    public RegistrationMailDeliveryOutbox encrypt(Long verificationId, String email, String code,
                                                   LocalDateTime now) {
        requireKey();
        String idempotencyKey = "registration-code:" + verificationId;
        RegistrationMailDeliveryPayload payload = new RegistrationMailDeliveryPayload(
                email, "MyTools register verification code",
                "Your MyTools verification code is " + code + ". It expires in 1 hour.");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            byte[] plaintext = objectMapper.writeValueAsBytes(payload);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, idempotencyKey);
            return new RegistrationMailDeliveryOutbox(verificationId, idempotencyKey,
                    Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(nonce),
                    "PENDING", 0, now, null, null, now, now);
        } catch (JsonProcessingException | GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to serialize registration mail payload", exception);
        }
    }

    /**
     * 解密真实投递载荷并验证记录绑定关系。
     *
     * @param record Outbox 记录
     * @return 真实投递载荷
     */
    public RegistrationMailDeliveryPayload decrypt(RegistrationMailDeliveryOutbox record) {
        requireKey();
        try {
            byte[] nonce = Base64.getDecoder().decode(record.nonce());
            byte[] ciphertext = Base64.getDecoder().decode(record.encryptedPayload());
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, record.idempotencyKey());
            return objectMapper.readValue(plaintext, RegistrationMailDeliveryPayload.class);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt registration mail payload", exception);
        }
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce, String idempotencyKey)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }

    private void requireKey() {
        if (secretKey == null) {
            throw new IllegalStateException("Registration mail delivery encryption key is not configured");
        }
    }
}
