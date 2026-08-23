package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 书库索引重建视图。
 */
public record LibraryRebuildView(UUID id, long ownerId, Instant snapshotAt, int batchSize, String status,
                                 UUID taskId, long indexedCount, UUID lastCursor, String lastErrorCode,
                                 Instant createdAt, Instant updatedAt) {
}
