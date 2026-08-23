package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 书源健康检查视图。
 *
 * @param id 请求标识
 * @param taskId 任务标识
 * @param status 状态
 * @param keyword 探测词
 * @param checked 已检查数量
 * @param healthy 健康数量
 * @param unhealthy 异常数量
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record HealthCheckView(UUID id, UUID taskId, String status, String keyword, int checked, int healthy,
                              int unhealthy, Instant createdAt, Instant updatedAt) {
}
