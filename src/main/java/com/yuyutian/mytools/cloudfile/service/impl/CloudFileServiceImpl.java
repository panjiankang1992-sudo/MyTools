package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.cloudfile.model.*;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.WebdavAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudFileServiceImpl implements CloudFileService {

    private static final String AES_KEY = "CJ0Xkfbp2KtWq0uZ0ckCCtGIOZU7NPC9ZXenbcZGZG8=";

    private static final Pattern TEXT_EXT_PATTERN = Pattern.compile(
            ".*\\.(txt|md|json|xml|html|htm|css|js|ts|py|java|cpp|c|h|sh|yaml|yml|properties)$",
            Pattern.CASE_INSENSITIVE);

    private final WebdavAccountMapper webdavAccountMapper;

    @Override
    public CloudFileListResponse listFiles(Long userId, Long accountId, String path, int depth) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            return client.list(path, depth);
        } catch (Exception e) {
            throw new BusinessException("50001", "无法连接到云盘服务: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public String getFileContent(Long userId, Long accountId, String path) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            if (!detectTextFile(path)) {
                throw new BusinessException("50001", "不支持预览该类型文件", HttpStatus.BAD_REQUEST);
            }
            return client.getContent(path);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("50001", "读取文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public byte[] downloadFile(Long userId, Long accountId, String path) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            return client.getBytes(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "下载文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public FileOperationResponse uploadFile(Long userId, Long accountId, String dirPath, String filename, byte[] content) {
        WebdavClient client = buildClient(userId, accountId);
        String cleanDir = dirPath == null || dirPath.equals("/") ? "" : dirPath;
        String fullPath = (cleanDir.isEmpty() ? "" : cleanDir) + "/" + filename;
        try {
            CloudFileItem item = client.put(fullPath, content);
            return new FileOperationResponse(item.getName(), item.getPath(), item.getSize(), item.getLastModified());
        } catch (Exception e) {
            throw new BusinessException("50001", "上传文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void createDirectory(Long userId, Long accountId, String path) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            client.mkdir(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "创建目录失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void rename(Long userId, Long accountId, String path, String newName) {
        int lastSlash = path.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
        String newPath = parent.equals("/") ? "/" + newName : parent + "/" + newName;
        move(userId, accountId, path, newPath);
    }

    @Override
    public void move(Long userId, Long accountId, String from, String to) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            client.move(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "移动失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void copy(Long userId, Long accountId, String from, String to) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            client.copy(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "复制失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void delete(Long userId, Long accountId, String path, boolean recursive) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            client.delete(path, recursive);
        } catch (Exception e) {
            throw new BusinessException("50001", "删除失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void saveTextFile(Long userId, Long accountId, String path, String content) {
        WebdavClient client = buildClient(userId, accountId);
        try {
            client.put(path, content);
        } catch (Exception e) {
            throw new BusinessException("50001", "保存文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private WebdavClient buildClient(Long userId, Long accountId) {
        WebdavAccount account;
        if (accountId != null) {
            account = webdavAccountMapper.selectById(accountId);
            if (account == null || !account.getUserId().equals(userId)) {
                throw new BusinessException("40002", "账号不存在或无权访问", HttpStatus.BAD_REQUEST);
            }
        } else {
            account = webdavAccountMapper.selectDefaultByUserId(userId);
            if (account == null) {
                throw new BusinessException("40001", "请先在 WebDAV 管理中配置账号", HttpStatus.BAD_REQUEST);
            }
        }
        String plainPassword = "";
        if (account.getPassword() != null && !account.getPassword().isBlank()) {
            try {
                plainPassword = AesEncryptUtils.decrypt(account.getPassword(), AES_KEY);
            } catch (Exception e) {
                log.error("Failed to decrypt WebDAV password for user {}", userId);
                throw new BusinessException("50001", "WebDAV 配置无效", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        return new WebdavClient(account.getUrl(), account.getUsername(), plainPassword);
    }

    private boolean detectTextFile(String path) {
        if (path == null) return false;
        return TEXT_EXT_PATTERN.matcher(path).matches();
    }
}
