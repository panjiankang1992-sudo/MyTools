package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 任务定义视图。
 *
 * @param id 标识
 * @param name 名称
 * @param description 描述
 * @param taskType 类型
 * @param timeoutSeconds 超时时间
 * @param clusterId 集群标识
 * @param cronExpression Cron 表达式
 * @param cronTimezone Cron 时区
 * @param executionMode 执行方式
 * @param enabled 是否启用
 * @param maxConcurrency 最大并发数
 * @param overlapPolicy 重叠策略
 * @param misfirePolicy 错过调度策略
 * @param parameterSchema 参数 Schema
 * @param resultSchema 结果 Schema
 * @param version 版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TaskDefinitionView(
        UUID id,
        String name,
        String description,
        TaskType taskType,
        long timeoutSeconds,
        UUID clusterId,
        String cronExpression,
        String cronTimezone,
        ExecutionMode executionMode,
        boolean enabled,
        int maxConcurrency,
        String overlapPolicy,
        String misfirePolicy,
        Map<String, Object> parameterSchema,
        Map<String, Object> resultSchema,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
