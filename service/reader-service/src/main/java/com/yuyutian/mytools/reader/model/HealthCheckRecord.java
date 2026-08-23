package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 书源健康检查持久化记录。
 *
 * @param id 请求标识
 * @param ownerId 所有者标识
 * @param idempotencyKey 幂等键
 * @param keyword 探测词
 * @param status 状态
 * @param taskId 任务标识
 * @param parameters 调度参数快照
 * @param checked 已检查数量
 * @param healthy 健康数量
 * @param unhealthy 异常数量
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record HealthCheckRecord(UUID id, long ownerId, String idempotencyKey, String keyword, String status,
                                UUID taskId, Map<String, Object> parameters, int checked, int healthy,
                                int unhealthy, Instant createdAt, Instant updatedAt) {
}
