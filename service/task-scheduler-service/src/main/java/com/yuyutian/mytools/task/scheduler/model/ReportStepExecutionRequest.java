package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * 上报步骤执行结果请求。
 *
 * @param reportRequestId 稳定上报请求标识
 * @param leaseToken 租约令牌
 * @param stepDefinitionId 步骤定义标识
 * @param attempt 尝试次数
 * @param status 结果状态
 * @param exitCode 退出码
 * @param result 结构化结果
 * @param errorCode 错误码
 * @param errorMessage 错误摘要
 * @param logIndex 日志分段索引
 */
public record ReportStepExecutionRequest(
        @NotNull UUID reportRequestId,
        @NotNull UUID leaseToken,
        @NotNull UUID stepDefinitionId,
        @Min(1) int attempt,
        @NotNull TaskStatus status,
        Integer exitCode,
        @NotNull Map<String, Object> result,
        String errorCode,
        String errorMessage,
        @NotNull Map<String, Object> logIndex
) {
    /**
     * 规范化迁移期旧 Executor 未携带的日志索引。
     */
    public ReportStepExecutionRequest {
        logIndex = logIndex == null ? Map.of() : Map.copyOf(logIndex);
    }

    /**
     * 创建兼容旧 Java 调用方的步骤上报请求。
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
    public ReportStepExecutionRequest(UUID leaseToken, UUID stepDefinitionId, int attempt, TaskStatus status,
                                      Integer exitCode, Map<String, Object> result, String errorCode,
                                      String errorMessage) {
        this(UUID.randomUUID(), leaseToken, stepDefinitionId, attempt, status, exitCode, result,
                errorCode, errorMessage, Map.of());
    }

    /**
     * 创建兼容带稳定请求标识的旧 Java 调用方步骤上报请求。
     */
    public ReportStepExecutionRequest(UUID reportRequestId, UUID leaseToken, UUID stepDefinitionId, int attempt,
                                      TaskStatus status, Integer exitCode, Map<String, Object> result,
                                      String errorCode, String errorMessage) {
        this(reportRequestId, leaseToken, stepDefinitionId, attempt, status, exitCode, result,
                errorCode, errorMessage, Map.of());
    }
}
