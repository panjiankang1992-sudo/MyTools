package com.yuyutian.mytools.automation.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 处理标准入站消息事件请求。
 */
public record ProcessMessageRequest(@NotNull UUID messageId) {
}
