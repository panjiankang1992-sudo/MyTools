package com.yuyutian.mytools.auth.messaging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注册邮件新消息服务旁路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.messaging.registration-mail")
public class RegistrationMailSidecarProperties {
    private boolean enabled;
    private String serviceUrl = "http://127.0.0.1:23250";
    private String internalToken = "";
}
