package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 章节缓存内部写入批次。
 */
public record ChapterCacheBatchRequest(@NotEmpty @Size(max = 20) List<@Valid Chapter> chapters) {

    /**
     * 单个章节缓存内容。
     */
    public record Chapter(@PositiveOrZero int index,
                          @NotBlank @Size(max = 500) String title,
                          @NotBlank @Size(max = 4096) String chapterUrl,
                          @NotBlank @Size(max = 10_485_760) String content,
                          @NotBlank @Size(min = 64, max = 64) String sha256,
                          @Positive long sizeBytes,
                          @NotNull @Positive Long ttlSeconds) {
    }
}
