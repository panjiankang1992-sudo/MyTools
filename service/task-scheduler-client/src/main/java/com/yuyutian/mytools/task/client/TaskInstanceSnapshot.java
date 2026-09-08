package com.yuyutian.mytools.task.client;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 任务实例公共快照。
 *
 * @param id 任务标识
 * @param taskName 任务名称
 * @param idempotencyKey 幂等键
 * @param parentTaskInstanceId 父任务标识
 * @param priority 优先级
 * @param requiredNodeLabels 节点标签约束
 * @param status 状态
 * @param startedAt 首次开始时间
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param parameters 参数
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TaskInstanceSnapshot(UUID id, String taskName, String idempotencyKey, UUID parentTaskInstanceId,
                                   String businessType, String businessId, int priority,
                                   Map<String, Object> parameters, Map<String, Object> requiredNodeLabels,
                                   String status, Instant startedAt, Instant createdAt, Instant updatedAt) {
}
