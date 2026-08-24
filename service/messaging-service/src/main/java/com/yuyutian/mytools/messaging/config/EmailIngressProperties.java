package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IMAP 入站账户运行配置。
 */
@ConfigurationProperties(prefix = "messaging.email-ingress")
public record EmailIngressProperties(boolean enabled, long ownerId, String accountKey, String host, int port,
                                     boolean ssl, String username, String password, String mailbox,
                                     int batchSize, long maximumMessageBytes) {
}
