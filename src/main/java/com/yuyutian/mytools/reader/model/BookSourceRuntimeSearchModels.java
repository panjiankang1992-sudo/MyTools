package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 后端书源规则执行搜索的数据模型。
 */
public final class BookSourceRuntimeSearchModels {

    private BookSourceRuntimeSearchModels() {
    }

    public record StartRequest(@NotBlank @Size(max = 100) String keyword,
                               @Min(1) @Max(100) int page,
                               @Pattern(regexp = "EXACT|FUZZY|PROBE") String mode) {
    }

    public record SearchResult(String name, String author, String intro, String lastChapter,
                               String coverUrl, String bookUrl, String sourceUrl, String sourceName) {
    }

    public record Task(String taskId, String status, int processedSources, int totalSources,
                       int cachedSources, int pendingSources, int failedSources,
                       int resultCount, List<SearchResult> results, int nextOffset,
                       boolean hasMore, String message, long updatedAt) {
    }
}
