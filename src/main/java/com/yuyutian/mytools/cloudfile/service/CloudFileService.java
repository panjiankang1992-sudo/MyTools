package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.*;

/**
 * 云文件服务接口，提供对云盘（WebDAV）的统一文件操作能力。
 *
 * @author mytools
 * @since 2026-05-20
 */
public interface CloudFileService {

    /**
     * 列出目录下的文件列表。
     *
     * @param userId 用户ID
     * @param path   目录路径（相对于云盘根目录）
     * @param depth  递归深度（0=仅当前目录）
     * @return 目录内容响应
     */
    CloudFileListResponse listFiles(Long userId, String path, int depth);

    /**
     * 获取文件内容（文本预览）。
     *
     * @param userId 用户ID
     * @param path   文件路径
     * @return 文件文本内容
     */
    String getFileContent(Long userId, String path);

    /**
     * 下载文件，返回字节流。
     *
     * @param userId 用户ID
     * @param path   文件路径
     * @return 文件字节数组
     */
    byte[] downloadFile(Long userId, String path);

    /**
     * 上传文件。
     *
     * @param userId   用户ID
     * @param dirPath  上传到的目录路径
     * @param filename 文件名
     * @param content  文件内容字节数组
     * @return 文件操作响应
     */
    FileOperationResponse uploadFile(Long userId, String dirPath, String filename, byte[] content);

    /**
     * 创建目录。
     *
     * @param userId 用户ID
     * @param path   目录路径
     */
    void createDirectory(Long userId, String path);

    /**
     * 重命名文件或目录。
     *
     * @param userId  用户ID
     * @param path    原路径
     * @param newName 新名称
     */
    void rename(Long userId, String path, String newName);

    /**
     * 移动文件或目录。
     *
     * @param userId 用户ID
     * @param from   源路径
     * @param to     目标路径
     */
    void move(Long userId, String from, String to);

    /**
     * 复制文件或目录。
     *
     * @param userId 用户ID
     * @param from   源路径
     * @param to     目标路径
     */
    void copy(Long userId, String from, String to);

    /**
     * 删除文件或目录。
     *
     * @param userId    用户ID
     * @param path      路径
     * @param recursive 是否递归删除（目录时需为true）
     */
    void delete(Long userId, String path, boolean recursive);
}
