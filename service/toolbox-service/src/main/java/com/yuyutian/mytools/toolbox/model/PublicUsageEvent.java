package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 匿名使用事件；字段与契约 6.1 节一致，所有字段先按原文接收再逐条校验。
 *
 * @param eventId 事件标识
 * @param eventType 事件类型
 * @param category 工具分类
 * @param toolId 工具标识，可为空
 * @param occurredAt 客户端事件时间
 * @param operationId 操作标识，可为空
 * @param schemaVersion 事件结构版本
 */
public record PublicUsageEvent(
        /** 事件标识。 */
        @JsonProperty("eventId") String eventId,
        /** 事件类型。 */
        @JsonProperty("eventType") String eventType,
        /** 工具分类。 */
        @JsonProperty("category") String category,
        /** 工具标识，可为空。 */
        @JsonProperty("toolId") String toolId,
        /** 客户端事件时间。 */
        @JsonProperty("occurredAt") String occurredAt,
        /** 操作标识，可为空。 */
        @JsonProperty("operationId") String operationId,
        /** 事件结构版本。 */
        @JsonProperty("schemaVersion") Integer schemaVersion) {
}
