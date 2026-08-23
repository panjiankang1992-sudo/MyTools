package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * 上报步骤执行结果请求。
 *
 * @param leaseToken 租约令牌
 * @param stepDefinitionId 步骤定义标识
 * @param attempt 尝试次数
 * @param status 结果状态
 * @param exitCode 退出码
 * @param result 结构化结果
 * @param errorCode 错误码
 * @param errorMessage 错误摘要
 */
public record ReportStepExecutionRequest(
        @NotNull UUID leaseToken,
        @NotNull UUID stepDefinitionId,
        @Min(1) int attempt,
        @NotNull TaskStatus status,
        Integer exitCode,
        @NotNull Map<String, Object> result,
        String errorCode,
        String errorMessage
) {
}
