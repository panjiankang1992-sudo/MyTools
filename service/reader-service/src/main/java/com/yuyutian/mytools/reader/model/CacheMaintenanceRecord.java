package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 章节缓存维护持久化记录。
 */
public record CacheMaintenanceRecord(UUID id, String idempotencyKey, String maintenanceType, Instant cutoffAt,
                                     int batchSize, String status, UUID taskId, long deletedCount,
                                     String lastErrorCode, Instant createdAt, Instant updatedAt) {
}
