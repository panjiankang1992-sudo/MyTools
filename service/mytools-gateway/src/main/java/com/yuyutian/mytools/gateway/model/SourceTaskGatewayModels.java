package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Gateway 对外书源维护任务模型。
 */
public final class SourceTaskGatewayModels {

    private SourceTaskGatewayModels() {
    }

    /**
     * 创建书源发现请求。
     */
    public record CreateDiscovery(@NotBlank @Size(max = 255) String idempotencyKey,
                                  @NotBlank @Size(max = 4096) String url) {
    }

    /**
     * 书源发现业务视图。
     */
    public record DiscoveryView(UUID id, String status, String url, int processed, int saved,
                                int rejected, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 创建健康检查请求。
     */
    public record CreateHealthCheck(@NotBlank @Size(max = 255) String idempotencyKey,
                                    @NotBlank @Size(max = 200) String keyword) {
    }

    /**
     * 健康检查业务视图。
     */
    public record HealthCheckView(UUID id, String status, String keyword, int checked, int healthy,
                                  int unhealthy, Instant createdAt, Instant updatedAt) {
    }
}
