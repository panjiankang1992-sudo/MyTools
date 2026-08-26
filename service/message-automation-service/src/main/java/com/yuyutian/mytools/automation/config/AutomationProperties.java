package com.yuyutian.mytools.automation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息自动化服务配置。
 */
@ConfigurationProperties(prefix = "automation")
public record AutomationProperties(String internalToken, String messagingUrl, String messagingToken,
                                   String downloadUrl, String downloadToken,
                                   String qqConnectorUrl, String qqConnectorToken,
                                   boolean completionRelayEnabled, int completionRelayBatchSize,
                                   int reconciliationBatchSize) {
}
