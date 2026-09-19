package com.yuyutian.mytools.auth.messaging;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * 注册邮件稳定灰度路由器。
 */
@Component
public class RegistrationMailRoutingService {

    private static final int BUCKET_COUNT = 100;
    private final String mode;
    private final int canaryPercent;
    private final byte[] routingKey;

    /**
     * 创建注册邮件路由器并校验灰度配置。
     *
     * @param properties 注册邮件迁移配置
     */
    public RegistrationMailRoutingService(RegistrationMailSidecarProperties properties) {
        this.mode = properties.getMode().trim().toUpperCase(Locale.ROOT);
        this.canaryPercent = properties.getCanaryPercent();
        this.routingKey = properties.getRoutingKey().getBytes(StandardCharsets.UTF_8);
        if (!mode.equals("LEGACY") && !mode.equals("SHADOW")
                && !mode.equals("CANARY") && !mode.equals("PRIMARY")) {
            throw new IllegalStateException("Unsupported registration mail routing mode");
        }
        if (canaryPercent < 0 || canaryPercent > BUCKET_COUNT) {
            throw new IllegalStateException("Registration mail canary percent must be between 0 and 100");
        }
        if (mode.equals("CANARY") && routingKey.length < 32) {
            throw new IllegalStateException("Registration mail routing key must contain at least 32 bytes");
        }
    }

    /**
     * 按规范化邮箱稳定选择唯一真实投递路径。
     *
     * @param email 收件邮箱
     * @return 唯一投递路由
     */
    public RegistrationMailRoute route(String email) {
        if (mode.equals("LEGACY") || mode.equals("SHADOW")) {
            return RegistrationMailRoute.LEGACY;
        }
        if (mode.equals("PRIMARY")) {
            return RegistrationMailRoute.MESSAGING;
        }
        byte[] digest = hmac(email.trim().toLowerCase(Locale.ROOT));
        int bucket = Math.floorMod(ByteBuffer.wrap(digest, 0, Long.BYTES).getLong(), BUCKET_COUNT);
        return bucket < canaryPercent ? RegistrationMailRoute.MESSAGING : RegistrationMailRoute.LEGACY;
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(routingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate registration mail route", exception);
        }
    }
}
