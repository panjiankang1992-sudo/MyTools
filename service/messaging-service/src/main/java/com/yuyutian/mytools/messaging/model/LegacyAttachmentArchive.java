package com.yuyutian.mytools.messaging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 旧消息附件的不可变归档引用。
 *
 * @param fileName 文件名
 * @param mimeType 媒体类型
 * @param size 文件大小
 * @param sha256 内容摘要
 * @param archiveRef 归档引用
 */
public record LegacyAttachmentArchive(
        @NotBlank @Size(max = 1024) String fileName,
        @Size(max = 255) String mimeType,
        @PositiveOrZero long size,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
        @NotBlank @Size(max = 2048) String archiveRef) {
}
