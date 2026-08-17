package com.yuyutian.mytools.drive.model;

import java.time.LocalDateTime;

/**
 * App 可见的不透明网盘文件条目。
 */
public record DriveItemView(
        String itemId,
        String name,
        String kind,
        String mimeType,
        long sizeBytes,
        LocalDateTime modifiedAt) {
}
