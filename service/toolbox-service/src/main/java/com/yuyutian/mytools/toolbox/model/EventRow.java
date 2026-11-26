package com.yuyutian.mytools.toolbox.model;

import java.time.Instant;

/**
 * 已规范化的事件持久化行。
 *
 * @param id 自增主键
 * @param siteId 站点标识
 * @param guestId 访客标识
 * @param eventId 事件标识
 * @param toolId 工具标识，可为空
 * @param category 工具分类
 * @param eventType 事件类型
 * @param source 事件来源
 * @param operationId 操作标识，可为空
 * @param occurredAt 客户端事件时间
 * @param receivedAt 服务端接收时间
 */
public record EventRow(
        /** 自增主键。 */
        long id,
        /** 站点标识。 */
        String siteId,
        /** 访客标识。 */
        String guestId,
        /** 事件标识。 */
        String eventId,
        /** 工具标识，可为空。 */
        String toolId,
        /** 工具分类。 */
        ToolCategory category,
        /** 事件类型。 */
        String eventType,
        /** 事件来源。 */
        String source,
        /** 操作标识，可为空。 */
        String operationId,
        /** 客户端事件时间。 */
        Instant occurredAt,
        /** 服务端接收时间。 */
        Instant receivedAt) {
}
