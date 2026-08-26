package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 书源目录与正文执行契约。
 */
public final class BookSourceRuntimeModels {
    private BookSourceRuntimeModels() {
    }

    /** 目录请求。 */
    public record CatalogRequest(@NotNull Long ownerId,
                                 @NotBlank @Size(max = 4096) String sourceUrl,
                                 @NotBlank @Size(max = 4096) String bookUrl) {
    }

    /** 正文请求。 */
    public record ContentRequest(@NotNull Long ownerId,
                                 @NotBlank @Size(max = 4096) String sourceUrl,
                                 @NotBlank @Size(max = 4096) String chapterUrl,
                                 @Min(0) @Max(1000000) int chapterIndex) {
    }

    /** 章节描述。 */
    public record Chapter(String title, String resourceUri, int index) {
    }

    /** 图书目录。 */
    public record Catalog(String name, String author, String intro, String coverUrl,
                          String latestChapter, List<Chapter> chapters) {
    }

    /** 章节正文。 */
    public record Content(String kind, String text, List<String> imageUrls) {
    }
}
