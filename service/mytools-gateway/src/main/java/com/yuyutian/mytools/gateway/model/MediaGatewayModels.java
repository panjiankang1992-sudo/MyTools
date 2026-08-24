package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Media Gateway 的稳定请求和响应模型。
 */
public final class MediaGatewayModels {
    private MediaGatewayModels() {
    }

    /**
     * 媒体查询结果。
     */
    public record MediaView(UUID id, long ownerId, UUID assetId, String displayName,
                            String mimeType, long sizeBytes, String contentSha256,
                            String status, long version, List<String> tags) {
    }

    /**
     * 媒体分页结果。
     */
    public record MediaPage(List<MediaView> items, UUID nextAfterId) {
    }

    /**
     * 播放进度写入请求，不允许客户端指定 owner。
     */
    public record ProgressRequest(@PositiveOrZero long positionMs,
                                  @PositiveOrZero long durationMs,
                                  boolean completed,
                                  @PositiveOrZero long expectedRevision,
                                  @NotNull Instant clientUpdatedAt) {
    }

    /**
     * 播放进度响应。
     */
    public record ProgressView(long ownerId, UUID mediaItemId, long positionMs,
                               long durationMs, boolean completed, long revision,
                               Instant clientUpdatedAt, Instant serverUpdatedAt) {
    }
}
