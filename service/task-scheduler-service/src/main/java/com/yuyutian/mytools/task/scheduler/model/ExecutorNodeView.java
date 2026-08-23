package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 执行节点视图。
 *
 * @param id 标识
 * @param name 名称
 * @param instanceId 启动实例标识
 * @param status 状态
 * @param capabilities 能力
 * @param labels 标签
 * @param maxConcurrentTasks 最大并发数
 * @param runningTasks 运行任务数
 * @param enabled 是否启用
 * @param lastHeartbeatAt 最后心跳时间
 */
public record ExecutorNodeView(
        UUID id,
        String name,
        String instanceId,
        NodeStatus status,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        int maxConcurrentTasks,
        int runningTasks,
        boolean enabled,
        Instant lastHeartbeatAt
) {
}
