package com.yuyutian.mytools.cloudfile.service.impl;

import com.yuyutian.mytools.cloudfile.model.*;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.utils.AesEncryptUtils;
import com.yuyutian.mytools.webdav.mapper.WebdavAccountMapper;
import com.yuyutian.mytools.webdav.model.WebdavAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudFileServiceImpl implements CloudFileService {

    private static final String ALIST_TYPE = "alist";
    private static final Pattern SINGLE_RANGE_PATTERN = Pattern.compile("^bytes=(?:\\d+-\\d*|-\\d+)$");

    private static final Pattern TEXT_EXT_PATTERN = Pattern.compile(
            ".*\\.(txt|md|json|xml|html|htm|css|js|ts|py|java|cpp|c|h|sh|yaml|yml|properties)$",
            Pattern.CASE_INSENSITIVE);

    private final WebdavAccountMapper webdavAccountMapper;

    @Value("${alist.internal-url:}")
    private String alistInternalUrl;

    @Value("${alist.public-url:}")
    private String alistPublicUrl;

    @Value("${mytools.encryption.key:}")
    private String encryptionKey;

    @Value("${mytools.encryption.previous-key:}")
    private String previousEncryptionKey;

    @Override
    public CloudFileListResponse listFiles(Long userId, Long accountId, String path, int depth) {
        WebdavAccount account = resolveAccount(userId, accountId);
        try {
            if (ALIST_TYPE.equals(account.getType())) {
                AlistClient client = buildAuthenticatedAlistClient(account);
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
        validateUploadTarget(dirPath, filename);
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

    /**
     * 从Multipart输入流上传文件，不复制完整内容到JVM堆。
     */
    @Override
    public FileOperationResponse uploadFileStream(Long userId, Long accountId, String dirPath, String filename,
                                                  InputStream content, long contentLength) {
        validateUploadTarget(dirPath, filename);
        WebdavAccount account = resolveAccount(userId, accountId);
        WebdavClient client = buildClient(account);
        String cleanDir = (dirPath == null || dirPath.equals("/") || dirPath.isEmpty())
                ? "" : dirPath.replaceAll("/+$", "");
        String fullPath = (cleanDir.isEmpty() ? "" : cleanDir + "/") + filename;
        try {
            CloudFileItem item = client.put(fullPath, content, contentLength);
            return new FileOperationResponse(item.getName(), item.getPath(), item.getSize(), item.getLastModified());
        } catch (Exception exception) {
            throw new BusinessException("50001", "上传文件失败: " + exception.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void validateUploadTarget(String directory, String filename) {
        String normalizedDirectory = directory == null ? "" : directory.trim();
        if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\") || filename.indexOf('\0') >= 0
                || (!normalizedDirectory.isEmpty() && !normalizedDirectory.startsWith("/"))
                || normalizedDirectory.contains("/../") || normalizedDirectory.endsWith("/..")) {
            throw new BusinessException(ErrorCode.FILE_005);
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
        WebdavAccount account = resolveAlistAccount(userId, accountId);
        AlistClient client = buildAuthenticatedAlistClient(account);
        try {
            String rawUrl = client.getRawUrl(path);
            String apiUrl = resolveAlistApiUrl(account.getUrl());
            if (!apiUrl.equals(normalizeBaseUrl(account.getUrl())) && rawUrl.startsWith(apiUrl)) {
                // 本机 API 返回的直链主机浏览器不可达，替换回公开 Alist 地址。
                return normalizeBaseUrl(account.getUrl()) + rawUrl.substring(apiUrl.length());
            }
            return rawUrl;
        } catch (Exception e) {
            throw new BusinessException("53001", "获取预览链接失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 打开可直接转发给移动端的远程媒体流。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @param rangeHeader 客户端 Range 请求头
     * @return 远程媒体流
     */
    @Override
    public RemoteMediaStream openMediaStream(Long userId, Long accountId, String path, String rangeHeader) {
        WebdavAccount account = resolveAccount(userId, accountId);
        String normalizedRange = normalizeRange(rangeHeader);
        try {
            HttpResponse<InputStream> response = ALIST_TYPE.equals(account.getType())
                    ? buildAuthenticatedAlistClient(account).openStream(path, normalizedRange)
                    : buildClient(account).openStream(path, normalizedRange);
            return new RemoteMediaStream(
                    response.body(),
                    response.statusCode(),
                    response.headers().firstValue("Content-Type"),
                    response.headers().firstValue("Content-Length"),
                    response.headers().firstValue("Content-Range"),
                    response.headers().firstValue("Accept-Ranges"),
                    response.headers().firstValue("ETag"),
                    response.headers().firstValue("Last-Modified"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.MEDIA_002);
        }
    }

    /**
     * 打开WebDAV或Alist远程文件下载流。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @return 远程文件流
     */
    @Override
    public RemoteMediaStream openDownloadStream(Long userId, Long accountId, String path) {
        WebdavAccount account = resolveAccount(userId, accountId);
        try {
            HttpResponse<InputStream> response = ALIST_TYPE.equals(account.getType())
                    ? buildAuthenticatedAlistClient(account).openStream(path)
                    : buildClient(account).openStream(path, null);
            return new RemoteMediaStream(
                    response.body(),
                    response.statusCode(),
                    response.headers().firstValue("Content-Type"),
                    response.headers().firstValue("Content-Length"),
                    response.headers().firstValue("Content-Range"),
                    response.headers().firstValue("Accept-Ranges"),
                    response.headers().firstValue("ETag"),
                    response.headers().firstValue("Last-Modified"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MEDIA_002);
        }
    }

    private String normalizeRange(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        String normalized = rangeHeader.trim();
        if (!SINGLE_RANGE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.MEDIA_003);
        }
        return normalized;
    }

    private WebdavAccount resolveAlistAccount(Long userId, Long accountId) {
        WebdavAccount account;
        if (accountId != null) {
            account = webdavAccountMapper.selectById(accountId);
            if (account == null || !account.getUserId().equals(userId)
                    || !ALIST_TYPE.equals(account.getType()) || !Integer.valueOf(1).equals(account.getIsActive())) {
                throw new BusinessException("40002", "Alist 账号不存在或无权访问", HttpStatus.BAD_REQUEST);
            }
            return account;
        }

        account = webdavAccountMapper.selectActiveAlistByUserId(userId);
        if (account == null) {
            throw new BusinessException("40001", "请先配置 Alist 账号", HttpStatus.BAD_REQUEST);
        }
        return account;
    }

    private AlistClient buildAuthenticatedAlistClient(WebdavAccount account) {
        try {
            String plainPassword = decrypt(account.getPassword());
            AlistClient client = new AlistClient(resolveAlistApiUrl(account.getUrl()), account.getUsername(), "");
            client.login(plainPassword);
            return client;
        } catch (Exception e) {
            log.error("Failed to refresh Alist token", e);
            throw new BusinessException("53002", "Alist 登录失败，请检查账号配置", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String resolveAlistApiUrl(String accountUrl) {
        String normalizedAccountUrl = normalizeBaseUrl(accountUrl);
        if (alistInternalUrl != null && !alistInternalUrl.isBlank()
                && alistPublicUrl != null && !alistPublicUrl.isBlank()
                && normalizedAccountUrl.equals(normalizeBaseUrl(alistPublicUrl))) {
            return normalizeBaseUrl(alistInternalUrl);
        }
        return normalizedAccountUrl;
    }

    private String normalizeBaseUrl(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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
            return AesEncryptUtils.decryptWithKeyRing(encrypted, encryptionKey, previousEncryptionKey);
        } catch (Exception e) {
            throw new BusinessException("50001", "密码解密失败，请检查账号配置", HttpStatus.INTERNAL_SERVER_ERROR);
        }
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
