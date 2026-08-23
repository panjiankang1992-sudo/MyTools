package com.yuyutian.mytools.asset.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 内容资产核心记录。
 */
public record AssetRecord(UUID id, String contentSha256, long sizeBytes, String mimeType,
                          String status, long version, Instant createdAt, Instant updatedAt) {
}
