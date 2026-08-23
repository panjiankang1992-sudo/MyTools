package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 阅读标记同步状态视图。
 */
public record MarkerStateView(UUID id, UUID shelfBookId, long ownerId, String bookKey, String markerType,
                              int chapterIndex, Map<String, Object> position, String note, boolean deleted,
                              long version, Instant createdAt, Instant updatedAt) {
}
