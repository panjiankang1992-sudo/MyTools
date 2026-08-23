package com.yuyutian.mytools.automation.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 从 Messaging Service 读取的标准入站消息。
 */
public record InboundMessage(UUID id, long ownerId, ChannelType channelType, String externalMessageId,
                             String conversationKey, String sender, String subject, String body,
                             Instant receivedAt, Instant createdAt) {
}
