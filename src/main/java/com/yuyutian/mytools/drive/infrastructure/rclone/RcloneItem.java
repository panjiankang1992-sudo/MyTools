package com.yuyutian.mytools.drive.infrastructure.rclone;

import java.time.OffsetDateTime;

/**
 * rclone 返回的受限文件元数据。
 */
public record RcloneItem(
        String path,
        String name,
        long size,
        String mimeType,
        OffsetDateTime modifiedAt,
        boolean directory,
        String id) {
}
