package com.yuyutian.mytools.automation.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建授权自动化规则及其下载动作绑定。
 */
public record CreateAutomationRuleRequest(
        @NotNull Long ownerId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]+$") @Size(max = 128) String name,
        @NotNull ChannelType channelType,
        @Size(max = 512) String conversationKey,
        @Size(max = 1024) String sender,
        @NotNull @Size(max = 128) String commandPrefix,
        @NotBlank @Pattern(regexp = "^(HTTP_ASSET|MESSAGE_ATTACHMENT)$") String requestKind,
        @Min(1) @Max(20) int maxActions,
        @Min(0) @Max(1000) int priority,
        boolean enabled) {
}
