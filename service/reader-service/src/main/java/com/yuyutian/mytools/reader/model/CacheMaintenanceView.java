package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 章节缓存维护任务视图。
 */
public record CacheMaintenanceView(UUID id, String maintenanceType, Instant cutoffAt, int batchSize,
                                   String status, UUID taskId, long deletedCount, String lastErrorCode,
                                   Instant createdAt, Instant updatedAt) {
}
