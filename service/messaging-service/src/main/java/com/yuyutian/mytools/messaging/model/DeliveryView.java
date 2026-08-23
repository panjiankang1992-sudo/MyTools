package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 对外投递状态视图，不暴露消息正文。
 */
public record DeliveryView(UUID id, ChannelType channelType, String recipient, String status, UUID taskId,
                           String providerMessageId, String lastErrorCode, Instant createdAt, Instant updatedAt) {
}
