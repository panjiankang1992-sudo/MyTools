package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;

/**
 * 标准化入站消息写入请求。
 */
public record CreateInboundMessageRequest(@NotNull Long ownerId,
                                          @NotNull ChannelType channelType,
                                          @NotBlank @Size(max = 512) String externalMessageId,
                                          @NotBlank @Size(max = 512) String conversationKey,
                                          @NotBlank @Size(max = 1024) String sender,
                                          @Size(max = 998) String subject,
                                          @NotBlank @Size(max = 10_485_760) String body,
                                          @NotNull Instant receivedAt,
                                          @Valid @Size(max = 500) List<CreateInboundMessagePart> parts) {

    /**
     * 创建不含结构化分段的兼容请求。
     */
    public CreateInboundMessageRequest(Long ownerId, ChannelType channelType, String externalMessageId,
                                       String conversationKey, String sender, String subject, String body,
                                       Instant receivedAt) {
        this(ownerId, channelType, externalMessageId, conversationKey, sender, subject, body, receivedAt, List.of());
    }

    /**
     * 返回不可变且非空的消息分段。
     */
    @Override
    public List<CreateInboundMessagePart> parts() {
        return parts == null ? List.of() : List.copyOf(parts);
    }
}
