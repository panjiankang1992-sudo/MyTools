package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;

import java.util.UUID;

/**
 * 分配集群节点请求。
 *
 * @param nodeId 节点标识
 * @param weight 权重
 * @param priority 优先级
 * @param enabled 是否启用
 */
public record AssignClusterNodeRequest(UUID nodeId, @Min(1) int weight, @Min(0) int priority, boolean enabled) {
}
