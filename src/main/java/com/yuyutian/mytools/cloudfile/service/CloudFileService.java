package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.*;
import java.io.InputStream;

/**
 * 云文件服务接口，提供对云盘（WebDAV）的统一文件操作能力。
 *
 * @author mytools
 * @since 2026-05-20
 */
public interface CloudFileService {

    CloudFileListResponse listFiles(Long userId, Long accountId, String path, int depth);

    String getFileContent(Long userId, Long accountId, String path);

    byte[] downloadFile(Long userId, Long accountId, String path);

    FileOperationResponse uploadFile(Long userId, Long accountId, String dirPath, String filename, byte[] content);

    /**
     * 从输入流上传远程文件，避免在服务端堆内存中保存完整内容。
     */
    FileOperationResponse uploadFileStream(Long userId, Long accountId, String dirPath, String filename,
                                           InputStream content, long contentLength);

    void createDirectory(Long userId, Long accountId, String path);

    void rename(Long userId, Long accountId, String path, String newName);

    void move(Long userId, Long accountId, String from, String to);

    void copy(Long userId, Long accountId, String from, String to);

    void delete(Long userId, Long accountId, String path, boolean recursive);

    void saveTextFile(Long userId, Long accountId, String path, String content);

    String alistRawUrl(Long userId, Long accountId, String path);

    /**
     * 打开支持 Range 的远程媒体流。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @param rangeHeader 客户端 Range 请求头
     * @return 远程媒体流
     */
    RemoteMediaStream openMediaStream(Long userId, Long accountId, String path, String rangeHeader);

    /**
     * 打开远程文件下载流，支持WebDAV与Alist。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @return 远程文件流
     */
    RemoteMediaStream openDownloadStream(Long userId, Long accountId, String path);
}
