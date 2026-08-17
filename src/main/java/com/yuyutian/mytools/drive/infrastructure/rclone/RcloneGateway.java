package com.yuyutian.mytools.drive.infrastructure.rclone;

import java.util.List;

/**
 * 只开放网盘业务需要的 rclone 操作，不允许透传命令。
 */
public interface RcloneGateway {

    /**
     * 列出一个远端目录的直接子项。
     *
     * @param remoteKey 服务端配置的远端键
     * @param path 远端相对路径
     * @return 直接子项
     */
    List<RcloneItem> list(String remoteKey, String path);

    /**
     * 递归统计一个远端目录。
     *
     * @param remoteKey 服务端配置的远端键
     * @param path 远端相对路径
     * @return 文件数量和字节数
     */
    RcloneDirectorySize size(String remoteKey, String path);

    /**
     * 打开指定文件的字节范围。
     *
     * @param remoteKey 服务端配置的远端键
     * @param path 远端相对路径
     * @param offset 起始偏移
     * @param count 读取长度，负数表示读取至文件末尾
     * @return 内容流
     */
    RcloneContent open(String remoteKey, String path, long offset, long count);
}
