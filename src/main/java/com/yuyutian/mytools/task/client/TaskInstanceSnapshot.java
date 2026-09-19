package com.yuyutian.mytools.task.client;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 统一任务网关返回的任务实例快照。
 *
 * @param id 任务实例标识
 * @param taskName 任务名称
 * @param idempotencyKey 幂等键
 * @param parentTaskInstanceId 父任务标识
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param priority 优先级
 * @param parameters 任务参数
 * @param requiredNodeLabels 节点标签约束
 * @param status 任务状态
 * @param startedAt 首次开始时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TaskInstanceSnapshot(
        UUID id, String taskName, String idempotencyKey, UUID parentTaskInstanceId,
        String businessType, String businessId, int priority, Map<String, Object> parameters,
        Map<String, Object> requiredNodeLabels, String status, Instant startedAt,
        Instant createdAt, Instant updatedAt
) {
}
