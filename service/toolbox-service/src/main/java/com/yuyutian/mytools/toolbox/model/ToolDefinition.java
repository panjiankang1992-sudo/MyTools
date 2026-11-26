package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具注册表条目，序列化字段与契约第 2 节一致。
 *
 * @param id 稳定工具标识
 * @param category 一级分类
 * @param route 前端逻辑路由
 * @param labelKey 名称文案键
 * @param descriptionKey 说明文案键
 * @param icon 图标名称
 * @param enabled 是否对访客开放
 * @param order 分类内展示顺序
 */
public record ToolDefinition(
        /** 稳定工具标识。 */
        @JsonProperty("id") String id,
        /** 一级分类。 */
        @JsonProperty("category") ToolCategory category,
        /** 前端逻辑路由。 */
        @JsonProperty("route") String route,
        /** 名称文案键。 */
        @JsonProperty("labelKey") String labelKey,
        /** 说明文案键。 */
        @JsonProperty("descriptionKey") String descriptionKey,
        /** 图标名称。 */
        @JsonProperty("icon") String icon,
        /** 是否对访客开放。 */
        @JsonProperty("enabled") boolean enabled,
        /** 分类内展示顺序。 */
        @JsonProperty("order") int order) {
}
