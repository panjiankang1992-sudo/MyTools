package com.yuyutian.mytools.messaging.model;

/**
 * 入站消息 Outbox 死信计数。
 *
 * @param messageReceived 终态消息事件数量
 * @param oneBotForwardAccepted OneBot 受理回复事件数量
 * @param total 总数量
 */
public record InboundOutboxDeadCount(int messageReceived, int oneBotForwardAccepted, int total) {
}
