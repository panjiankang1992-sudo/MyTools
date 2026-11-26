package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 能力声明：说明某个分类或服务能力当前是否可用、在何处执行以及关闭原因键。
 *
 * @param id 能力标识，取分类值或服务名
 * @param enabled 是否可用
 * @param execution 执行位置：server 或 client
 * @param reasonKey 不可用原因键，可为空
 */
public record ToolCapability(
        /** 能力标识，取分类值或服务名。 */
        @JsonProperty("id") String id,
        /** 是否可用。 */
        @JsonProperty("enabled") boolean enabled,
        /** 执行位置：server 或 client。 */
        @JsonProperty("execution") String execution,
        /** 不可用原因键，可为空。 */
        @JsonProperty("reasonKey") String reasonKey) {
}
