package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 任务步骤视图。
 *
 * @param id 标识
 * @param taskDefinitionId 任务定义标识
 * @param name 名称
 * @param description 描述
 * @param stepKind 种类
 * @param scriptPackage 脚本包
 * @param scriptVersion 脚本版本
 * @param entrypoint 入口
 * @param argumentsTemplate 参数模板
 * @param enabled 是否启用
 * @param timeoutSeconds 超时时间
 * @param failurePolicy 失败策略
 * @param sequenceNumber 顺序号
 * @param maxAttempts 最大尝试次数
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TaskStepView(
        UUID id,
        UUID taskDefinitionId,
        String name,
        String description,
        StepKind stepKind,
        String scriptPackage,
        String scriptVersion,
        String entrypoint,
        List<String> argumentsTemplate,
        boolean enabled,
        long timeoutSeconds,
        FailurePolicy failurePolicy,
        int sequenceNumber,
        int maxAttempts,
        Instant createdAt,
        Instant updatedAt
) {
}
