package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 本地对象校验和操作。
 *
 * @param id 操作标识
 * @param rootId 受管根标识
 * @param rootName 受管根名称
 * @param idempotencyKey 幂等键
 * @param relativePath 根内相对路径
 * @param status 状态
 * @param taskInstanceId 任务实例标识
 * @param sizeBytes 文件大小
 * @param contentSha256 内容摘要
 * @param errorCode 错误码
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ChecksumOperation(UUID id, UUID rootId, String rootName, String idempotencyKey,
                                String relativePath, String status, UUID taskInstanceId, Long sizeBytes,
                                String contentSha256, String errorCode, Instant createdAt, Instant updatedAt) {
}
