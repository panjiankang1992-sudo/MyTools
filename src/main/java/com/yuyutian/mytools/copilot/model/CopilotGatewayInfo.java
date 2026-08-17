package com.yuyutian.mytools.copilot.model;

/**
 * Copilot网关公开配置。
 *
 * @param enabled 网关是否可用。
 * @param model Agent Core应投影的模型标识。
 */
public record CopilotGatewayInfo(boolean enabled, String model) {
}
