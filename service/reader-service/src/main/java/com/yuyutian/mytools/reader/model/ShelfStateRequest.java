package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 创建或乐观更新书架状态请求。
 */
public record ShelfStateRequest(@NotNull @Positive Long ownerId,
                                @NotBlank @Size(max = 512) String bookKey,
                                @NotNull Map<String, Object> metadata,
                                boolean deleted,
                                @Positive Long expectedVersion) {
}
