package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 搜索请求持久化记录。
 *
 * @param id 请求标识
 * @param ownerId 所有者标识
 * @param idempotencyKey 幂等键
 * @param keyword 关键字
 * @param mode 匹配模式
 * @param page 页码
 * @param status 状态
 * @param taskId 任务标识
 * @param parameters 调度参数快照
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record SearchRecord(UUID id, long ownerId, String idempotencyKey, String keyword, SearchMode mode,
                           int page, String status, UUID taskId, Map<String, Object> parameters,
                           Instant createdAt, Instant updatedAt) {
}
