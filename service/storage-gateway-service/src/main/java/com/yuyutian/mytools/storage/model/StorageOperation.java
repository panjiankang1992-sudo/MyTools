package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 异步存储操作聚合。
 *
 * @param id 标识
 * @param providerId Provider 标识
 * @param idempotencyKey 幂等键
 * @param operationType 类型
 * @param sourcePath 来源路径
 * @param status 状态
 * @param taskInstanceId 任务实例标识
 * @param itemCount 已合并对象数
 * @param maximumObjects 最大对象数
 * @param errorCode 错误码
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record StorageOperation(UUID id, UUID providerId, String idempotencyKey, String operationType,
                               String sourcePath, String status, UUID taskInstanceId, long itemCount,
                               int maximumObjects, String errorCode, Instant createdAt, Instant updatedAt) {
}
