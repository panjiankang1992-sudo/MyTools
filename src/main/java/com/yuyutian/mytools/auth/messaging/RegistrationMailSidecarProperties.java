package com.yuyutian.mytools.auth.messaging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注册邮件无副作用影子对账配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.messaging.registration-mail")
public class RegistrationMailSidecarProperties {
    private boolean enabled;
    private String mode = "LEGACY";
    private int canaryPercent;
    private String routingKey = "";
    private String deliveryEncryptionKey = "";
    private String serviceUrl = "http://127.0.0.1:23250";
    private String internalToken = "";
    private String hashKey = "";
    private int batchSize = 50;
    private int claimSeconds = 30;
    private int maxAttempts = 10;
    private int baseRetrySeconds = 5;
    private int maxRetrySeconds = 3600;
}
