package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息服务运行配置。
 */
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(String schedulerUrl, String internalToken, String mailFrom,
                                  String automationUrl, String automationToken,
                                  boolean automationRelayEnabled, int automationRelayBatchSize) {
}
