package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 阅读进度同步状态视图。
 */
public record ProgressStateView(UUID shelfBookId, long ownerId, String bookKey, int chapterIndex,
                                String chapterUrl, Map<String, Object> position, boolean deleted,
                                long version, Instant updatedAt) {
}
