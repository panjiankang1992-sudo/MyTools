package com.yuyutian.mytools.messaging.model;

import java.util.UUID;

/**
 * 入站消息 Outbox 受控重驱结果。
 *
 * @param eventId 事件标识
 * @param eventType 事件类型
 * @param status 当前状态
 * @param redriven 本次调用是否恢复了死信
 */
public record InboundOutboxRedriveView(UUID eventId, String eventType, String status, boolean redriven) {
}
