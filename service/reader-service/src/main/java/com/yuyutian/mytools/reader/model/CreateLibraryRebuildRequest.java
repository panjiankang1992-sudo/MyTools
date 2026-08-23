package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建书库索引重建请求。
 */
public record CreateLibraryRebuildRequest(@NotNull @Positive Long ownerId,
                                          @NotBlank @Size(max = 255) String idempotencyKey,
                                          @NotNull Instant snapshotAt,
                                          @Min(1) @Max(500) int batchSize) {
}
