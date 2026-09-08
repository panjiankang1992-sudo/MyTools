package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务事件 Outbox 投递配置。
 *
 * @param webhookUrl 事件接收地址
 * @param authToken 接收端鉴权令牌
 * @param batchSize 单批上限
 * @param maxAttempts 最大投递次数
 * @param backlogAlertSeconds 积压健康告警阈值秒数
 */
@ConfigurationProperties(prefix = "task.outbox")
public record TaskOutboxProperties(String webhookUrl, String authToken, int batchSize, int maxAttempts,
                                   long backlogAlertSeconds) {
}
