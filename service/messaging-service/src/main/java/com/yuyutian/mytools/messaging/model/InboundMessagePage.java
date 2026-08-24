package com.yuyutian.mytools.messaging.model;

import java.util.List;
import java.util.UUID;

/**
 * 入站消息游标分页。
 *
 * @param items 消息列表
 * @param nextAfterId 下一页游标
 */
public record InboundMessagePage(List<InboundMessageView> items, UUID nextAfterId) {
}
