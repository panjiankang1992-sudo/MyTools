package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Executor 批量写入发现书源的内部请求。
 *
 * @param sources 原始书源快照
 */
public record SourceIngestRequest(@NotEmpty @Size(max = 100) List<Map<String, Object>> sources) {
}
