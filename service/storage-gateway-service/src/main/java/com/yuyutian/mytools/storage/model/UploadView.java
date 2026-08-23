package com.yuyutian.mytools.storage.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 受控上传状态视图。
 *
 * @param id 上传标识
 * @param rootName 受管根名称
 * @param relativePath 相对路径
 * @param status 状态
 * @param expectedSize 预期字节数
 * @param actualSize 实际字节数
 * @param sha256 实际摘要
 * @param storageUri 稳定存储 URI
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record UploadView(UUID id, String rootName, String relativePath, String status, long expectedSize,
                         Long actualSize, String sha256, String storageUri, Instant createdAt, Instant updatedAt) {
}
