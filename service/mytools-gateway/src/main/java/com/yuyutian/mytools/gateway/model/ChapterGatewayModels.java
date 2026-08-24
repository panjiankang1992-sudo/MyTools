package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gateway 对外章节预取和缓存模型。
 */
public final class ChapterGatewayModels {

    private ChapterGatewayModels() {
    }

    /**
     * 创建章节预取请求。
     */
    public record CreatePrefetch(@NotBlank @Size(max = 255) String idempotencyKey,
                                 @NotNull UUID sourceId,
                                 @NotBlank @Size(max = 4096) String bookUrl,
                                 @NotEmpty @Size(max = 100)
                                 List<@NotNull @PositiveOrZero Integer> chapterIndexes) {
    }

    /**
     * 章节预取业务视图。
     */
    public record PrefetchView(UUID id, String status, int requestedCount, int cachedCount,
                               Instant createdAt, Instant updatedAt) {
    }

    /**
     * 章节缓存业务视图。
     */
    public record CacheView(UUID sourceId, String bookUrl, int chapterIndex, String chapterTitle,
                            String chapterUrl, String content, String sha256, long sizeBytes, Instant expiresAt) {
    }
}
