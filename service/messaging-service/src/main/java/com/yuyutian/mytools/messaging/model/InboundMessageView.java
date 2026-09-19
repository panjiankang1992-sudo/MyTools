package com.yuyutian.mytools.messaging.model;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * 标准化入站消息视图。
 *
 * @param id 消息标识
 * @param ownerId 所有者标识
 * @param channelType 渠道类型
 * @param externalMessageId 渠道消息标识
 * @param conversationKey 会话键
 * @param sender 发送方
 * @param subject 主题
 * @param body 正文
 * @param receivedAt 接收时间
 * @param createdAt 创建时间
 * @param parts 消息分段
 * @param preAcknowledged 是否已经通过持久化受理事件回复原会话
 */
public record InboundMessageView(UUID id, long ownerId, ChannelType channelType, String externalMessageId,
                                 String conversationKey, String sender, String subject, String body,
                                 Instant receivedAt, Instant createdAt, List<InboundMessagePart> parts,
                                 boolean preAcknowledged) {
}
