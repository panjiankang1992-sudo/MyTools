package com.yuyutian.mytools.drive.model;

import java.util.List;

/**
 * 网盘目录列表及当前目录统计。
 */
public record DriveDirectoryView(
        String driveId,
        String currentItemId,
        String name,
        long itemCount,
        long totalSizeBytes,
        List<DriveItemView> items) {
}
