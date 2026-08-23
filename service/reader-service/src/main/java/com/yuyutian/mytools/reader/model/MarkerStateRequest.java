package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * 创建或乐观更新阅读标记请求。
 */
public record MarkerStateRequest(@NotNull UUID markerId,
                                 @NotNull @Positive Long ownerId,
                                 @NotBlank @Size(max = 512) String bookKey,
                                 @NotBlank @Size(max = 32) String markerType,
                                 @Min(0) int chapterIndex,
                                 @NotNull Map<String, Object> position,
                                 @Size(max = 10000) String note,
                                 boolean deleted,
                                 @Positive Long expectedVersion) {
}
