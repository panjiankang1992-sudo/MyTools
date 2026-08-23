package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 章节预取持久化记录。
 */
public record ChapterPrefetchRecord(UUID id, long ownerId, String idempotencyKey, UUID sourceId,
                                    int sourceVersion, String bookUrl, String status, UUID taskId,
                                    Map<String, Object> parameters, int requestedCount, int cachedCount,
                                    Instant createdAt, Instant updatedAt) {
}
