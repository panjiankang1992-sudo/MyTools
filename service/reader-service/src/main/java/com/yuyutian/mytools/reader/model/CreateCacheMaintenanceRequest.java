package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建章节缓存维护任务请求。
 */
public record CreateCacheMaintenanceRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Pattern(regexp = "EXPIRED|STALE_SOURCE") String maintenanceType,
        @NotNull Instant cutoffAt,
        @Min(1) @Max(1000) int batchSize) {
}
