package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 投递请求持久化记录。
 */
public record DeliveryRecord(UUID id, long ownerId, String idempotencyKey, ChannelType channelType,
                             UUID accountId, String recipient, String subject, String body, String status,
                             UUID taskId, String providerMessageId, String lastErrorCode,
                             Instant createdAt, Instant updatedAt) {
}
