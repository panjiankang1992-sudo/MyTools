package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 后端书源规则执行阅读链路的数据模型。
 */
public final class BookSourceRuntimeReaderModels {

    private BookSourceRuntimeReaderModels() {
    }

    public record CatalogRequest(@NotBlank @Size(max = 4096) String sourceUrl,
                                 @NotBlank @Size(max = 4096) String bookUrl) {
    }

    public record ContentRequest(@NotBlank @Size(max = 4096) String sourceUrl,
                                 @NotBlank @Size(max = 4096) String chapterUrl,
                                 @Min(0) @Max(1000000) int chapterIndex) {
    }

    public record Chapter(String title, String resourceUri, int index) {
    }

    public record Catalog(String name, String author, String intro, String coverUrl,
                          String latestChapter, List<Chapter> chapters) {
    }

    public record Content(String kind, String text, List<String> imageUrls) {
    }
}
