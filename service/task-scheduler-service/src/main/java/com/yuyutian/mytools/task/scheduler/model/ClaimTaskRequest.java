package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 执行节点领取任务请求。
 *
 * @param nodeId 节点标识
 * @param instanceId 节点启动实例标识
 * @param claimRequestId 领取请求幂等标识
 * @param leaseSeconds 租约秒数
 */
public record ClaimTaskRequest(
        @NotNull UUID nodeId,
        @NotNull UUID instanceId,
        @NotNull UUID claimRequestId,
        @Min(10) @Max(3600) int leaseSeconds
) {
    /**
     * 创建兼容旧调用方的领取请求。
     */
    public ClaimTaskRequest(UUID nodeId, UUID instanceId, int leaseSeconds) {
        this(nodeId, instanceId, UUID.randomUUID(), leaseSeconds);
    }
}
