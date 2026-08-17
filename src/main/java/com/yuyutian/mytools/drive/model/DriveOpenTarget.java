package com.yuyutian.mytools.drive.model;

/**
 * 已完成用户归属校验的网盘文件打开目标。
 *
 * @param userId 用户ID
 * @param driveId 网盘ID
 * @param itemId 文件ID
 * @param remoteKey 服务端远端键
 * @param remotePath 远端相对路径
 * @param name 文件名
 * @param mimeType MIME类型
 * @param sizeBytes 文件大小
 */
public record DriveOpenTarget(Long userId, Long driveId, Long itemId, String remoteKey,
                              String remotePath, String name, String mimeType, long sizeBytes) {
}
