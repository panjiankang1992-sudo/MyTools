package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 受控上传持久化记录。
 *
 * @param id 上传标识
 * @param rootId 受管根标识
 * @param rootName 受管根名称
 * @param basePath 受管根路径
 * @param idempotencyKey 幂等键
 * @param relativePath 相对路径
 * @param expectedSize 预期字节数
 * @param expectedSha256 预期摘要
 * @param actualSize 实际字节数
 * @param actualSha256 实际摘要
 * @param status 状态
 * @param temporaryPath 临时路径
 * @param finalPath 最终路径
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record UploadRecord(UUID id, UUID rootId, String rootName, String basePath, String idempotencyKey,
                           String relativePath, long expectedSize, String expectedSha256, Long actualSize,
                           String actualSha256, String status, String temporaryPath, String finalPath,
                           Instant createdAt, Instant updatedAt) {
}
