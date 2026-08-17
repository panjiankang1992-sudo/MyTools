package com.yuyutian.mytools.drive.model;

/**
 * App 可见的网盘信息。
 */
public record DriveAccountView(String driveId, String name, boolean readOnly, String status) {
}
