package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 消息服务运行配置。
 */
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(String schedulerUrl, String internalToken, String mailFrom,
                                  String automationUrl, String automationToken,
                                  boolean automationRelayEnabled, int automationRelayBatchSize,
                                  int inboundOutboxClaimLeaseSeconds,
                                  boolean oneBotIngressEnabled, String downloadIngestionUrl,
                                  String downloadIngestionToken, String providerResolverUrl,
                                  String providerResolverToken, String qqConnectorUrl,
                                  String qqConnectorToken, String telegramConnectorUrl,
                                  String telegramConnectorToken) {

    private static final int DEFAULT_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS = 30;
    private static final int MINIMUM_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS = 30;
    private static final int MAXIMUM_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS = 300;

    /**
     * 返回经过安全边界约束的入站 Outbox 租约时长。
     *
     * @return 有界租约时长
     */
    public Duration inboundOutboxClaimLease() {
        int configured = inboundOutboxClaimLeaseSeconds <= 0
                ? DEFAULT_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS : inboundOutboxClaimLeaseSeconds;
        int bounded = Math.min(Math.max(configured, MINIMUM_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS),
                MAXIMUM_INBOUND_OUTBOX_CLAIM_LEASE_SECONDS);
        return Duration.ofSeconds(bounded);
    }
}
