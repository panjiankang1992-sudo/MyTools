package com.yuyutian.mytools.drive.infrastructure.rclone;

import java.io.InputStream;

/**
 * rclone 文件内容流，调用方负责关闭输入流。
 *
 * @param inputStream 内容输入流
 * @param contentLength 本次响应长度
 */
public record RcloneContent(InputStream inputStream, long contentLength) {
}
