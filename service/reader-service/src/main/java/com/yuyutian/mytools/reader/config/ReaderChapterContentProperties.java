package com.yuyutian.mytools.reader.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 受控目录与正文读取的部署级上限，公共请求不能覆盖。 */
@Validated
@ConfigurationProperties(prefix = "reader.chapter-content")
public record ReaderChapterContentProperties(
        @DefaultValue("50000") @Min(1) @Max(50000) int maximumCatalogChapters,
        @DefaultValue("33554432") @Min(1024) @Max(67108864) int maximumCatalogResponseBytes,
        @DefaultValue("2097152") @Min(1024) @Max(2097152) int maximumChapterResponseBytes,
        @DefaultValue("120000") @Min(1) @Max(120000) int maximumChapterCodepoints,
        @DefaultValue("30") @Min(1) @Max(120) int requestTimeoutSeconds) {

    /** 校验直接构建的实例，避免非 Spring 调用绕过容量约束。 */
    public ReaderChapterContentProperties {
        if (maximumCatalogChapters < 1 || maximumCatalogChapters > 50000
                || maximumCatalogResponseBytes < 1024 || maximumCatalogResponseBytes > 67108864
                || maximumChapterResponseBytes < 1024 || maximumChapterResponseBytes > 2097152
                || maximumChapterCodepoints < 1 || maximumChapterCodepoints > 120000
                || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid chapter content limits");
        }
    }
}
