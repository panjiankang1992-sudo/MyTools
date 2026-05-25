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
    private static final String ALIST_TYPE = "alist";

    private static final Pattern TEXT_EXT_PATTERN = Pattern.compile(
            ".*\\.(txt|md|json|xml|html|htm|css|js|ts|py|java|cpp|c|h|sh|yaml|yml|properties)$",
            Pattern.CASE_INSENSITIVE);

    private final WebdavAccountMapper webdavAccountMapper;

    @Override
    public CloudFileListResponse listFiles(Long userId, Long accountId, String path, int depth) {
        WebdavAccount account = resolveAccount(userId, accountId);
        try {
            if (ALIST_TYPE.equals(account.getType())) {
                AlistClient client = buildAlistClient(account);
                return client.list(path);
            } else {
                WebdavClient client = buildClient(account);
                return client.list(path, depth);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("50001", "无法连接到云盘服务: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public String getFileContent(Long userId, Long accountId, String path) {
        WebdavAccount account = resolveAccount(userId, accountId);
        if (ALIST_TYPE.equals(account.getType())) {
            throw new BusinessException("40002", "Alist 账号不支持文件内容预览", HttpStatus.BAD_REQUEST);
        }
        WebdavClient client = buildClient(account);
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
        WebdavAccount account = resolveAccount(userId, accountId);
        if (ALIST_TYPE.equals(account.getType())) {
            throw new BusinessException("40002", "Alist 账号不支持文件下载", HttpStatus.BAD_REQUEST);
        }
        WebdavClient client = buildClient(account);
        try {
            return client.getBytes(path);
        } catch (Exception e) {
            throw new BusinessException("50001", "下载文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public FileOperationResponse uploadFile(Long userId, Long accountId, String dirPath, String filename, byte[] content) {
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        String cleanDir = (dirPath == null || dirPath.equals("/") || dirPath.isEmpty()) ? "" : dirPath.replaceAll("/+$", "");
        String fullPath = (cleanDir.isEmpty() ? "" : cleanDir + "/") + filename;
        try {
            CloudFileItem item = client.put(fullPath, content);
            return new FileOperationResponse(item.getName(), item.getPath(), item.getSize(), item.getLastModified());
        } catch (Exception e) {
            throw new BusinessException("50001", "上传文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void createDirectory(Long userId, Long accountId, String path) {
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
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
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        try {
            client.move(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "移动失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void copy(Long userId, Long accountId, String from, String to) {
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        try {
            client.copy(from, to);
        } catch (Exception e) {
            throw new BusinessException("50001", "复制失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void delete(Long userId, Long accountId, String path, boolean recursive) {
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        try {
            client.delete(path, recursive);
        } catch (Exception e) {
            throw new BusinessException("50001", "删除失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void saveTextFile(Long userId, Long accountId, String path, String content) {
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        try {
            client.put(path, content);
        } catch (Exception e) {
            throw new BusinessException("50001", "保存文件失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public String alistRawUrl(Long userId, Long accountId, String path) {
        WebdavAccount account = resolveAccount(userId, accountId);
        AlistClient client = buildAlistClient(account);
        try {
            return client.getRawUrl(path);
        } catch (java.io.IOException e) {
            if ("TOKEN_EXPIRED".equals(e.getMessage())) {
                client = rebuildAlistClient(account);
                try {
                    return client.getRawUrl(path);
                } catch (Exception ex) {
                    throw new BusinessException("53001", "获取预览链接失败: " + ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
                }
            }
            throw new BusinessException("53001", "获取预览链接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            throw new BusinessException("53001", "获取预览链接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private AlistClient buildAlistClient(WebdavAccount account) {
        String token = decrypt(account.getPassword());
        return new AlistClient(account.getUrl(), account.getUsername(), token);
    }

    private AlistClient rebuildAlistClient(WebdavAccount account) {
        String newToken;
        try {
            String plainPassword = decrypt(account.getPassword());
            AlistClient tempClient = new AlistClient(account.getUrl(), account.getUsername(), "");
            newToken = tempClient.login(plainPassword);
            reSaveAlistToken(account.getId(), encrypt(newToken));
        } catch (Exception e) {
            log.error("Failed to refresh Alist token", e);
            throw new BusinessException("53002", "Alist 登录失败，请检查账号配置", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new AlistClient(account.getUrl(), account.getUsername(), newToken);
    }

    private WebdavAccount resolveAccount(Long userId, Long accountId) {
        WebdavAccount account;
        if (accountId != null) {
            account = webdavAccountMapper.selectById(accountId);
            if (account == null || !account.getUserId().equals(userId)) {
                throw new BusinessException("40002", "账号不存在或无权访问", HttpStatus.BAD_REQUEST);
            }
        } else {
            account = webdavAccountMapper.selectDefaultByUserId(userId);
            if (account == null) {
                throw new BusinessException("40001", "请先配置云盘账号", HttpStatus.BAD_REQUEST);
            }
        }
        return account;
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            return AesEncryptUtils.decrypt(encrypted, AES_KEY);
        } catch (Exception e) {
            throw new BusinessException("50001", "密码解密失败，请检查账号配置", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String encrypt(String plain) {
        try {
            return AesEncryptUtils.encrypt(plain, AES_KEY);
        } catch (Exception e) {
            throw new BusinessException("50001", "加密失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void reSaveAlistToken(Long accountId, String newToken) {
        webdavAccountMapper.updatePasswordById(accountId, newToken);
    }

    private void rejectAlistForWrite(WebdavAccount account) {
        if (ALIST_TYPE.equals(account.getType())) {
            throw new BusinessException("40002", "Alist 账号不支持该操作", HttpStatus.BAD_REQUEST);
        }
    }

    private WebdavClient buildClient(WebdavAccount account) {
        rejectAlistForWrite(account);
        String plainPassword = decrypt(account.getPassword());
        if (plainPassword.isEmpty() && account.getPassword() != null && !account.getPassword().isBlank()) {
            log.error("Failed to decrypt WebDAV password for user {}", account.getUserId());
            throw new BusinessException("50001", "WebDAV 配置无效", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new WebdavClient(account.getUrl(), account.getUsername(), plainPassword);
    }

    private boolean detectTextFile(String path) {
        if (path == null) return false;
        return TEXT_EXT_PATTERN.matcher(path).matches();
    }
}
