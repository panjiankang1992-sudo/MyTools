package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * 标准化入站消息视图。
 */
public record InboundMessageView(UUID id, long ownerId, ChannelType channelType, String externalMessageId,
                                 String conversationKey, String sender, String subject, String body,
                                 Instant receivedAt, Instant createdAt, List<InboundMessagePart> parts) {
}
