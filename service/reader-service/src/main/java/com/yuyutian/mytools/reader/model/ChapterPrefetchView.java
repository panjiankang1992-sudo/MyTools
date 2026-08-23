package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 章节预取任务视图。
 */
public record ChapterPrefetchView(UUID id, UUID taskId, String status, int requestedCount,
                                  int cachedCount, Instant createdAt, Instant updatedAt) {
}
