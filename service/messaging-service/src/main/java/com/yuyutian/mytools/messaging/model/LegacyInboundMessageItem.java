package com.yuyutian.mytools.messaging.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 旧消息服务导出的标准化历史入站消息。
 *
 * @param sourceSystem 来源系统
 * @param legacyMessageId 旧消息标识
 * @param ownerId 所有者
 * @param channelType 渠道类型
 * @param conversationKey 会话键
 * @param sender 发送方
 * @param subject 主题
 * @param body 正文
 * @param receivedAt 接收时间
 * @param parts 消息分段
 */
public record LegacyInboundMessageItem(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sourceSystem,
        @NotBlank @Size(max = 255) String legacyMessageId,
        @NotNull @PositiveOrZero Long ownerId,
        @NotNull ChannelType channelType,
        @NotBlank @Size(max = 512) String conversationKey,
        @NotBlank @Size(max = 1024) String sender,
        @Size(max = 998) String subject,
        @NotBlank @Size(max = 10_485_760) String body,
        @NotNull Instant receivedAt,
        @Valid @Size(max = 500) List<CreateInboundMessagePart> parts) {

    /**
     * 返回不可变分段集合。
     *
     * @return 消息分段
     */
    @Override
    public List<CreateInboundMessagePart> parts() {
        return parts == null ? List.of() : List.copyOf(parts);
    }

    /**
     * 转换为 Messaging 内部入站契约。
     *
     * @return 标准入站请求
     */
    public CreateInboundMessageRequest toInboundRequest() {
        return new CreateInboundMessageRequest(ownerId, channelType,
                "legacy:" + sourceSystem + ":" + legacyMessageId, conversationKey,
                sender, subject, body, receivedAt, parts());
    }
}
