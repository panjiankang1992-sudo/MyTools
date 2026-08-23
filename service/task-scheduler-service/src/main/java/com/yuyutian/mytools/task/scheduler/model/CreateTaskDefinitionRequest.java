package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * 创建任务定义请求。
 *
 * @param name 名称
 * @param description 描述
 * @param taskType 任务类型
 * @param timeoutSeconds 超时时间
 * @param clusterId 执行集群
 * @param cronExpression Cron 表达式
 * @param cronTimezone Cron 时区
 * @param executionMode 执行方式
 * @param enabled 是否启用
 * @param maxConcurrency 最大并发数
 * @param overlapPolicy 重叠策略
 * @param misfirePolicy 错过调度策略
 * @param parameterSchema 参数 Schema
 * @param resultSchema 结果 Schema
 */
public record CreateTaskDefinitionRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,127}$") String name,
        String description,
        @NotNull TaskType taskType,
        @Min(1) long timeoutSeconds,
        UUID clusterId,
        String cronExpression,
        String cronTimezone,
        @NotNull ExecutionMode executionMode,
        boolean enabled,
        @Min(1) int maxConcurrency,
        @NotBlank String overlapPolicy,
        @NotBlank String misfirePolicy,
        @NotNull Map<String, Object> parameterSchema,
        @NotNull Map<String, Object> resultSchema
) {
}
