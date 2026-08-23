package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 已缓存章节视图。
 */
public record ChapterCacheView(UUID sourceId, String bookUrl, int chapterIndex, String chapterTitle,
                               String chapterUrl, String content, String sha256, long sizeBytes,
                               Instant expiresAt) {
}
