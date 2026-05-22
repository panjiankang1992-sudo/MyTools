package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.*;

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

    void createDirectory(Long userId, Long accountId, String path);

    void rename(Long userId, Long accountId, String path, String newName);

    void move(Long userId, Long accountId, String from, String to);

    void copy(Long userId, Long accountId, String from, String to);

    void delete(Long userId, Long accountId, String path, boolean recursive);

    void saveTextFile(Long userId, Long accountId, String path, String content);

    String alistRawUrl(Long userId, Long accountId, String path);
}
