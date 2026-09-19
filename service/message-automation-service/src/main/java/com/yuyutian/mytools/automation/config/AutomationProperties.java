package com.yuyutian.mytools.automation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * 消息自动化服务配置。
 */
@ConfigurationProperties(prefix = "automation")
@Validated
public record AutomationProperties(String internalToken, String messagingUrl, String messagingToken,
                                   String downloadUrl, String downloadToken,
                                   boolean completionRelayEnabled, int completionRelayBatchSize,
                                   int reconciliationBatchSize, @Min(1) @Max(10) int completionRelayMaxAttempts,
                                   @Min(1) @Max(10000) int maxActionsPerMessage,
                                   @Min(1) @Max(32) int reconciliationConcurrency,
                                   @Min(5) @Max(900) int reconciliationLeaseSeconds,
                                   @Min(1) @Max(20) int reconciliationMaxAttempts,
                                   @Min(50) @Max(60000) int actionStatusPollDelayMs) {

    /**
     * 绑定完整消息自动化配置。
     */
    @ConstructorBinding
    public AutomationProperties {
    }

    /**
     * 兼容使用默认对账并发参数的内部构造调用。
     */
    public AutomationProperties(String internalToken, String messagingUrl, String messagingToken,
                                String downloadUrl, String downloadToken,
                                boolean completionRelayEnabled, int completionRelayBatchSize,
                                int reconciliationBatchSize, int completionRelayMaxAttempts,
                                int maxActionsPerMessage) {
        this(internalToken, messagingUrl, messagingToken, downloadUrl, downloadToken,
                completionRelayEnabled, completionRelayBatchSize, reconciliationBatchSize,
                completionRelayMaxAttempts, maxActionsPerMessage, 4, 180, 8, 250);
    }

    /**
     * 兼容使用默认动作轮询间隔的内部构造调用。
     */
    public AutomationProperties(String internalToken, String messagingUrl, String messagingToken,
                                String downloadUrl, String downloadToken,
                                boolean completionRelayEnabled, int completionRelayBatchSize,
                                int reconciliationBatchSize, int completionRelayMaxAttempts,
                                int maxActionsPerMessage, int reconciliationConcurrency,
                                int reconciliationLeaseSeconds, int reconciliationMaxAttempts) {
        this(internalToken, messagingUrl, messagingToken, downloadUrl, downloadToken,
                completionRelayEnabled, completionRelayBatchSize, reconciliationBatchSize,
                completionRelayMaxAttempts, maxActionsPerMessage, reconciliationConcurrency,
                reconciliationLeaseSeconds, reconciliationMaxAttempts, 250);
    }
}
