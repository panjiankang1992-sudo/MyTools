package com.yuyutian.mytools.media.task;

/**
 * 旧缩略图生成成功后的媒体处理旁路事件。
 *
 * @param fileId 文件标识
 * @param sourcePath 原文件路径
 * @param contentSha256 内容哈希
 * @param mimeType 媒体类型
 */
public record MediaProcessingSidecarRequested(
        Long fileId,
        String sourcePath,
        String contentSha256,
        String mimeType
) {
}
