package com.yuyutian.mytools.toolbox.service.events;

import com.yuyutian.mytools.toolbox.model.ToolCategory;
import java.time.Instant;

/**
 * 已通过注册表与时间校验的事件，可直接写入数据库。
 *
 * @param eventId     事件标识
 * @param eventType   事件类型
 * @param category    工具分类
 * @param toolId      工具标识，可为空
 * @param operationId 操作标识，可为空
 * @param occurredAt  客户端事件时间
 */
public record ValidatedEvent(
        String eventId,
        EventType eventType,
        ToolCategory category,
        String toolId,
        String operationId,
        Instant occurredAt) {
}
