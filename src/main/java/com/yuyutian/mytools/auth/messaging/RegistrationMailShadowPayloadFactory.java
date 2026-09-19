package com.yuyutian.mytools.auth.messaging;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 注册邮件影子证据构造器。
 */
@Component
public class RegistrationMailShadowPayloadFactory {

    private static final int MINIMUM_HASH_KEY_LENGTH = 32;
    private static final String SUBJECT = "MyTools register verification code";
    private final RegistrationMailSidecarProperties properties;

    /**
     * 创建影子证据构造器。
     *
     * @param properties 影子配置
     */
    public RegistrationMailShadowPayloadFactory(RegistrationMailSidecarProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断影子链路是否启用。
     *
     * @return 是否启用
     */
    public boolean isEnabled() {
        // SHADOW 模式必须自动写入脱敏证据，避免依赖第二个开关造成名义双跑但实际无对账数据。
        return properties.isEnabled() || "SHADOW".equalsIgnoreCase(properties.getMode());
    }

    /**
     * 构造只包含不可逆摘要的 Outbox 记录。
     *
     * @param verificationId 验证码记录标识
     * @param email 收件邮箱
     * @param code 验证码
     * @param now 创建时间
     * @return Outbox 记录
     */
    public RegistrationMailShadowOutbox create(Long verificationId, String email, String code,
                                                LocalDateTime now) {
        String hashKey = properties.getHashKey();
        if (hashKey == null || hashKey.length() < MINIMUM_HASH_KEY_LENGTH) {
            // 启用影子链路后必须使用足够强的密钥，禁止静默丢失审计证据。
            throw new IllegalStateException("Registration mail shadow hash key must contain at least 32 characters");
        }
        String body = "Your MyTools verification code is " + code + ". It expires in 1 hour.";
        return new RegistrationMailShadowOutbox(
                verificationId,
                "registration-code:" + verificationId,
                hmacSha256(email.strip().toLowerCase()),
                hmacSha256(SUBJECT + "\n" + body),
                "DELIVERED",
                "PENDING",
                0,
                now,
                null,
                null,
                now,
                now
        );
    }

    private String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getHashKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }
}
