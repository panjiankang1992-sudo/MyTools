package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * 入站消息回复受理结果。
 */
public record InboundReplyView(UUID messageId, ChannelType channelType, String status) {
}
