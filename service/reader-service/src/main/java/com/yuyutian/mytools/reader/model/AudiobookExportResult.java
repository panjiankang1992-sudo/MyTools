package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 执行器发布有声书归档后的受控回写结果。
 *
 * @param storageUri 受管归档地址
 * @param contentSha256 归档摘要
 * @param sizeBytes 归档字节数
 * @param chapterCount 归档章节数
 */
public record AudiobookExportResult(
        @NotBlank @Pattern(regexp = "^storage://[A-Za-z0-9][A-Za-z0-9._-]{0,127}/.+$") String storageUri,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String contentSha256,
        @Min(1) long sizeBytes,
        @Min(1) int chapterCount) {
}
