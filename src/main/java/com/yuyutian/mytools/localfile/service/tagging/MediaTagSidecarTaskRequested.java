package com.yuyutian.mytools.localfile.service.tagging;

/**
 * 旧标签成功后请求创建旁路标签任务的不可变事件。
 *
 * @param fileId 文件标识
 * @param filename 文件名
 * @param sourcePath 原文件路径
 * @param thumbnailPath 缩略图路径
 * @param mimeType MIME 类型
 * @param contentSha256 内容哈希
 */
public record MediaTagSidecarTaskRequested(
        Long fileId,
        String filename,
        String sourcePath,
        String thumbnailPath,
        String mimeType,
        String contentSha256
) {
}
