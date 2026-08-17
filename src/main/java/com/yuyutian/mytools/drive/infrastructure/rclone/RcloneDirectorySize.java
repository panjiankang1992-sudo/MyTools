package com.yuyutian.mytools.drive.infrastructure.rclone;

/**
 * rclone 统计的递归文件数量和字节数。
 */
public record RcloneDirectorySize(long count, long bytes) {
}
