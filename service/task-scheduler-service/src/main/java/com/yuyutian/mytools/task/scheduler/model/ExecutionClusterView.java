package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 执行集群视图。
 *
 * @param id 标识
 * @param name 名称
 * @param description 描述
 * @param dispatchStrategy 调度策略
 * @param maxConcurrentTasks 最大并发数
 * @param labels 标签
 * @param enabled 是否启用
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ExecutionClusterView(
        UUID id,
        String name,
        String description,
        String dispatchStrategy,
        int maxConcurrentTasks,
        Map<String, Object> labels,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
