package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 工具接口响应：注册表、执行位置、能力、限额与策略版本。
 *
 * @param policyVersion 公开策略版本
 * @param execution PDF 文件执行位置，固定为 server
 * @param tools 工具注册表
 * @param capabilities 分类能力
 * @param limits 限额声明
 */
public record ToolsResponse(
        /** 公开策略版本。 */
        @JsonProperty("policyVersion") String policyVersion,
        /** PDF 文件执行位置，固定为 server。 */
        @JsonProperty("execution") String execution,
        /** 工具注册表。 */
        @JsonProperty("tools") List<ToolDefinition> tools,
        /** 分类能力。 */
        @JsonProperty("capabilities") List<ToolCapability> capabilities,
        /** 限额声明。 */
        @JsonProperty("limits") ToolLimits limits) {
}
