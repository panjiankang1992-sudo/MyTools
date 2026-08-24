package com.yuyutian.mytools.automation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 从 Messaging Service 读取的标准入站消息。
 */
public record InboundMessage(UUID id, long ownerId, ChannelType channelType, String externalMessageId,
                             String conversationKey, String sender, String subject, String body,
                             Instant receivedAt, Instant createdAt, List<MessagePart> parts) {

    public InboundMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * 兼容不需要附件分段的内部调用。
     */
    public InboundMessage(UUID id, long ownerId, ChannelType channelType, String externalMessageId,
                          String conversationKey, String sender, String subject, String body,
                          Instant receivedAt, Instant createdAt) {
        this(id, ownerId, channelType, externalMessageId, conversationKey, sender, subject, body,
                receivedAt, createdAt, List.of());
    }

    /**
     * Automation 可使用的安全消息分段。
     */
    public record MessagePart(UUID id, int sequence, String type, String attachmentType,
                              String fileName, String mimeType, Long declaredSize) {
    }
}
