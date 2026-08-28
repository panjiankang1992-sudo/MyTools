package com.yuyutian.mytools.automation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 消息自动化服务配置。
 */
@ConfigurationProperties(prefix = "automation")
@Validated
public record AutomationProperties(String internalToken, String messagingUrl, String messagingToken,
                                   String downloadUrl, String downloadToken,
                                   boolean completionRelayEnabled, int completionRelayBatchSize,
                                   int reconciliationBatchSize,
                                   @Min(1) @Max(10000) int maxActionsPerMessage) {
}
