package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 执行节点领取任务请求。
 *
 * @param nodeId 节点标识
 * @param instanceId 节点启动实例标识
 * @param claimRequestId 领取请求幂等标识
 * @param leaseSeconds 租约秒数
 * @param childTaskOnly 是否只领取具有父任务的子任务
 * @param rootTaskOnly 是否只领取根任务
 * @param parentTaskInstanceIds 只领取这些父任务的直接子任务
 */
public record ClaimTaskRequest(
        @NotNull UUID nodeId,
        @NotNull UUID instanceId,
        @NotNull UUID claimRequestId,
        @Min(10) @Max(3600) int leaseSeconds,
        boolean childTaskOnly,
        boolean rootTaskOnly,
        List<UUID> parentTaskInstanceIds
) {
    /**
     * 规范化领取范围并拒绝互相冲突的过滤条件。
     */
    public ClaimTaskRequest {
        parentTaskInstanceIds = parentTaskInstanceIds == null ? List.of() : List.copyOf(parentTaskInstanceIds);
        if (parentTaskInstanceIds.size() > 64) {
            throw new IllegalArgumentException("Direct child claim parent limit was exceeded");
        }
        if (rootTaskOnly && (childTaskOnly || !parentTaskInstanceIds.isEmpty())) {
            throw new IllegalArgumentException("Root and child task claim scopes cannot be combined");
        }
    }

    /**
     * 创建兼容只指定子任务过滤的领取请求。
     */
    public ClaimTaskRequest(UUID nodeId, UUID instanceId, UUID claimRequestId, int leaseSeconds,
                            boolean childTaskOnly) {
        this(nodeId, instanceId, claimRequestId, leaseSeconds, childTaskOnly, false, List.of());
    }

    /**
     * 创建兼容未指定任务层级过滤的领取请求。
     */
    public ClaimTaskRequest(UUID nodeId, UUID instanceId, UUID claimRequestId, int leaseSeconds) {
        this(nodeId, instanceId, claimRequestId, leaseSeconds, false, false, List.of());
    }

    /**
     * 创建兼容旧调用方的领取请求。
     */
    public ClaimTaskRequest(UUID nodeId, UUID instanceId, int leaseSeconds) {
        this(nodeId, instanceId, UUID.randomUUID(), leaseSeconds, false, false, List.of());
    }
}
