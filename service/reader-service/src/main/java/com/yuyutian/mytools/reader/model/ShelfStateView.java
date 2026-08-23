package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 书架同步状态视图。
 */
public record ShelfStateView(UUID id, long ownerId, String bookKey, Map<String, Object> metadata,
                             boolean deleted, long version, Instant createdAt, Instant updatedAt) {
}
