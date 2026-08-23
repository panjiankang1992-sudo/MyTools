package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 书库索引重建持久化记录。
 */
public record LibraryRebuildRecord(UUID id, long ownerId, String idempotencyKey, Instant snapshotAt,
                                   int batchSize, String status, UUID taskId, long indexedCount,
                                   UUID lastCursor, String lastErrorCode, Instant createdAt, Instant updatedAt) {
}
