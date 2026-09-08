package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建整书有声书导出任务的可信请求。
 *
 * @param idempotencyKey 调用方幂等键
 * @param format 归档格式
 */
public record CreateAudiobookExportRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,255}$") String idempotencyKey,
        @NotNull AudiobookExportFormat format) {
}
