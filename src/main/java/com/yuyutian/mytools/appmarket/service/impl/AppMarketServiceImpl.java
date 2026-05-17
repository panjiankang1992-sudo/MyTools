package com.yuyutian.mytools.appmarket.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuyutian.mytools.appmarket.dto.*;
import com.yuyutian.mytools.appmarket.entity.AppFile;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import com.yuyutian.mytools.appmarket.mapper.AppFileMapper;
import com.yuyutian.mytools.appmarket.mapper.AppMarketMapper;
import com.yuyutian.mytools.appmarket.mapper.AppVersionMapper;
import com.yuyutian.mytools.appmarket.service.AppMarketService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.user.Mapper.UserMapper;
import com.yuyutian.mytools.user.Model.User;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 应用市场服务实现。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppMarketServiceImpl implements AppMarketService {

    private static final String FILE_BASE_PATH = "/opt/yuyutian/MyTools/app-market-files";

    private final AppMarketMapper appMarketMapper;
    private final AppVersionMapper appVersionMapper;
    private final AppFileMapper appFileMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public IPage<AppMarketListResponse> listApps(String type, String name, int page, int pageSize) {
        Page<AppMarket> pageParam = new Page<>(page, pageSize);
        IPage<AppMarket> pageResult = appMarketMapper.selectAppPage(pageParam, type, name);

        List<AppMarketListResponse> list = pageResult.getRecords().stream()
                .map(this::convertToListResponse)
                .collect(Collectors.toList());

        Page<AppMarketListResponse> result = new Page<>(page, pageSize, pageResult.getTotal());
        result.setRecords(list);
        return result;
    }

    @Override
    public AppMarketDetailResponse getAppDetail(String appId, Long currentUserId) {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        AppMarketDetailResponse response = convertToDetailResponse(app);

        // 判断是否为所有者
        response.setIsOwner(app.getUserId().equals(currentUserId));

        return response;
    }

    @Override
    @Transactional
    public AppMarket createApp(AppMarketCreateRequest request, MultipartFile file,
                               MultipartFile thumbnail, Long userId) throws IOException {
        AppMarket app = new AppMarket();
        app.setId(String.valueOf(snowflakeIdGenerator.nextId()));
        app.setUserId(userId);
        app.setName(request.getName());
        app.setType(com.yuyutian.mytools.appmarket.enums.AppType.fromValue(request.getType()));
        app.setVersion(request.getVersion());
        app.setContent(request.getContent());
        app.setInstallCmd(request.getInstallCmd());
        app.setDownloadUrl(request.getDownloadUrl());
        app.setStatus(com.yuyutian.mytools.appmarket.enums.AppStatus.PUBLISHED);

        appMarketMapper.insert(app);
        log.info("上架新应用: id={}, name={}, userId={}", app.getId(), app.getName(), userId);

        // 保存缩略图
        if (thumbnail != null && !thumbnail.isEmpty()) {
            saveFile(app.getId(), null, "thumbnail", thumbnail);
        }

        // 保存内容文件
        if (file != null && !file.isEmpty()) {
            String fileType = getFileType(request.getType());
            saveFile(app.getId(), null, fileType, file);
        }

        return app;
    }

    @Override
    @Transactional
    public AppMarket updateApp(String appId, AppMarketUpdateRequest request,
                                MultipartFile file, MultipartFile thumbnail,
                                Long userId) throws IOException {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        // 权限检查
        checkPermission(app, userId);

        // 保存历史版本
        AppVersion history = new AppVersion();
        history.setId(String.valueOf(snowflakeIdGenerator.nextId()));
        history.setAppId(appId);
        history.setVersion(app.getVersion());
        history.setContent(app.getContent());
        // 找到当前版本的文件
        List<AppFile> currentFiles = appFileMapper.selectCurrentFilesByAppId(appId);
        if (!currentFiles.isEmpty()) {
            history.setFileId(currentFiles.get(0).getId());
        }
        appVersionMapper.insert(history);
        log.info("保存历史版本: appId={}, version={}", appId, app.getVersion());

        // 更新主表
        app.setVersion(request.getVersion());
        app.setContent(request.getContent());
        app.setInstallCmd(request.getInstallCmd());
        app.setDownloadUrl(request.getDownloadUrl());
        appMarketMapper.updateById(app);
        log.info("更新应用: id={}, version={}", appId, request.getVersion());

        // 保存缩略图
        if (thumbnail != null && !thumbnail.isEmpty()) {
            // 删除旧的缩略图
            deleteFilesByType(appId, null, "thumbnail");
            saveFile(appId, null, "thumbnail", thumbnail);
        }

        // 保存内容文件
        if (file != null && !file.isEmpty()) {
            String fileType = getFileType(app.getType().getValue());
            deleteFilesByType(appId, null, fileType);
            saveFile(appId, null, fileType, file);
        }

        return app;
    }

    @Override
    @Transactional
    public void deleteApp(String appId, Long currentUserId) {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        // 权限检查
        checkPermission(app, currentUserId);

        // 删除所有文件（磁盘和数据库）
        List<AppFile> allFiles = appFileMapper.selectAllByAppId(appId);
        for (AppFile file : allFiles) {
            deleteFileFromDisk(file.getFilePath());
            appFileMapper.deleteById(file.getId());
        }

        // 删除历史版本
        List<AppVersion> versions = appVersionMapper.selectByAppId(appId);
        for (AppVersion v : versions) {
            appVersionMapper.deleteById(v.getId());
        }

        // 删除主表记录
        appMarketMapper.deleteById(appId);
        log.info("删除应用: id={}", appId);
    }

    @Override
    @Transactional
    public void offlineApp(String appId, Long currentUserId) {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        checkPermission(app, currentUserId);

        app.setStatus(com.yuyutian.mytools.appmarket.enums.AppStatus.DRAFT);
        appMarketMapper.updateById(app);
        log.info("下架应用: id={}", appId);
    }

    @Override
    @Transactional
    public AppFile uploadFile(String appId, String fileType, MultipartFile file, Long userId) throws IOException {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        checkPermission(app, userId);

        // 删除同类型旧文件
        deleteFilesByType(appId, null, fileType);

        return saveFile(appId, null, fileType, file);
    }

    @Override
    public AppFile getFile(String fileId) {
        AppFile file = appFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.APP_007);
        }
        return file;
    }

    @Override
    @Transactional
    public void deleteFile(String appId, String fileId, Long userId) {
        AppMarket app = appMarketMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.APP_001);
        }

        checkPermission(app, userId);

        AppFile file = appFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.APP_007);
        }

        deleteFileFromDisk(file.getFilePath());
        appFileMapper.deleteById(fileId);
        log.info("删除文件: id={}", fileId);
    }

    @Override
    public List<AppVersion> getVersions(String appId) {
        return appVersionMapper.selectByAppId(appId);
    }

    @Override
    public AppVersion getVersionDetail(String appId, String versionId) {
        AppVersion version = appVersionMapper.selectById(versionId);
        if (version == null || !version.getAppId().equals(appId)) {
            throw new BusinessException(ErrorCode.APP_008);
        }
        return version;
    }

    @Override
    public String getFileDownloadPath(String fileId) {
        AppFile file = appFileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ErrorCode.APP_007);
        }
        return file.getFilePath();
    }

    // ========== 私有方法 ==========

    private void checkPermission(AppMarket app, Long userId) {
        // 需要管理员或应用所有者权限
        // 注意：此处简化处理，实际应从 SecurityContext 获取用户角色
        // 暂时只检查所有者，管理员权限在 Controller 层通过 @PreAuthorize 控制
        if (!app.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.APP_002);
        }
    }

    private AppFile saveFile(String appId, String versionId, String fileType, MultipartFile file) throws IOException {
        // 确保目录存在
        Path dir = Paths.get(FILE_BASE_PATH, appId, fileType);
        Files.createDirectories(dir);

        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path filePath = dir.resolve(uniqueFilename);

        // 保存文件
        file.transferTo(filePath.toFile());

        // 保存数据库记录
        AppFile appFile = new AppFile();
        appFile.setId(String.valueOf(snowflakeIdGenerator.nextId()));
        appFile.setAppId(appId);
        appFile.setVersionId(versionId);
        appFile.setFileType(fileType);
        appFile.setFileName(originalFilename != null ? originalFilename : uniqueFilename);
        appFile.setFilePath(filePath.toString());
        appFile.setFileSize(file.getSize());
        appFileMapper.insert(appFile);

        log.info("保存文件: id={}, appId={}, type={}, name={}", appFile.getId(), appId, fileType, originalFilename);
        return appFile;
    }

    private void deleteFileFromDisk(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.debug("删除磁盘文件: {}", filePath);
            }
        } catch (IOException e) {
            log.warn("删除磁盘文件失败: {}, error: {}", filePath, e.getMessage());
        }
    }

    private void deleteFilesByType(String appId, String versionId, String fileType) {
        List<AppFile> files;
        if (versionId != null) {
            files = appFileMapper.selectByVersionId(versionId);
        } else {
            files = appFileMapper.selectCurrentFilesByAppId(appId);
        }
        for (AppFile file : files) {
            if (fileType.equals(file.getFileType())) {
                deleteFileFromDisk(file.getFilePath());
                appFileMapper.deleteById(file.getId());
            }
        }
    }

    private String getFileType(String appType) {
        return switch (appType.toLowerCase()) {
            case "cli" -> "binary";
            case "mcp" -> "json";
            case "skill" -> "zip";
            case "app" -> "html";
            default -> "binary";
        };
    }

    private AppMarketListResponse convertToListResponse(AppMarket app) {
        AppMarketListResponse response = new AppMarketListResponse();
        response.setId(app.getId());
        response.setName(app.getName());
        response.setType(app.getType() != null ? app.getType().getValue() : null);
        response.setVersion(app.getVersion());
        response.setStatus(app.getStatus() != null ? app.getStatus().getValue() : null);
        response.setUserId(app.getUserId());
        response.setCreatedTime(app.getCreatedTime());
        response.setUpdateTime(app.getUpdateTime());

        // 获取用户名
        User user = userMapper.findById(app.getUserId());
        if (user != null) {
            response.setUserName(user.getUsername());
        }

        // 获取缩略图
        List<AppFile> files = appFileMapper.selectCurrentFilesByAppId(app.getId());
        for (AppFile file : files) {
            if ("thumbnail".equals(file.getFileType())) {
                response.setThumbnailId(file.getId());
                response.setThumbnailUrl("/market-files/" + app.getId() + "/thumbnail/" + file.getFileName());
            }
        }

        // 内容摘要
        if (app.getContent() != null) {
            String text = app.getContent().replaceAll("<[^>]+>", "").trim();
            response.setContentPreview(text.length() > 50 ? text.substring(0, 50) + "..." : text);
        }

        return response;
    }

    private AppMarketDetailResponse convertToDetailResponse(AppMarket app) {
        AppMarketDetailResponse response = new AppMarketDetailResponse();
        response.setId(app.getId());
        response.setName(app.getName());
        response.setType(app.getType() != null ? app.getType().getValue() : null);
        response.setVersion(app.getVersion());
        response.setContent(app.getContent());
        response.setInstallCmd(app.getInstallCmd());
        response.setDownloadUrl(app.getDownloadUrl());
        response.setStatus(app.getStatus() != null ? app.getStatus().getValue() : null);
        response.setUserId(app.getUserId());
        response.setCreatedTime(app.getCreatedTime());
        response.setUpdateTime(app.getUpdateTime());

        // 获取用户名
        User user = userMapper.findById(app.getUserId());
        if (user != null) {
            response.setUserName(user.getUsername());
        }

        // 获取文件信息
        List<AppFile> files = appFileMapper.selectCurrentFilesByAppId(app.getId());
        for (AppFile file : files) {
            if ("thumbnail".equals(file.getFileType())) {
                response.setThumbnailId(file.getId());
                response.setThumbnailPath(file.getFilePath());
                response.setThumbnailUrl("/market-files/" + app.getId() + "/thumbnail/" + file.getFileName());
            } else {
                response.setFileId(file.getId());
                response.setFileName(file.getFileName());
                response.setFileSize(file.getFileSize());
                response.setFileType(file.getFileType());
            }
        }

        return response;
    }
}
