package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 创建或乐观更新阅读进度请求。
 */
public record ProgressStateRequest(@NotNull @Positive Long ownerId,
                                   @NotBlank @Size(max = 512) String bookKey,
                                   @Min(0) int chapterIndex,
                                   @Size(max = 4096) String chapterUrl,
                                   @NotNull Map<String, Object> position,
                                   boolean deleted,
                                   @Positive Long expectedVersion) {
}
