package com.yuyutian.mytools.appmarket.service;

import com.yuyutian.mytools.appmarket.dto.*;
import com.yuyutian.mytools.appmarket.entity.AppFile;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 应用市场 Service 接口。
 *
 * @author mytools
 * @since 2026-05-16
 */
public interface AppMarketService {

    /**
     * 分页查询应用列表。
     */
    AppMarketPageResponse listApps(String type, String name, int page, int pageSize);

    /**
     * 获取应用详情。
     */
    AppMarketDetailResponse getAppDetail(String appId, Long currentUserId);

    /**
     * 上架新应用。
     */
    AppMarket createApp(AppMarketCreateRequest request, MultipartFile file,
                        MultipartFile thumbnail, Long userId) throws IOException;

    /**
     * 编辑应用（自动保存历史版本）。
     */
    AppMarket updateApp(String appId, AppMarketUpdateRequest request,
                         MultipartFile file, MultipartFile thumbnail,
                         Long userId) throws IOException;

    /**
     * 删除应用（含文件清理）。
     */
    void deleteApp(String appId, Long currentUserId);

    /**
     * 下架应用。
     */
    void offlineApp(String appId, Long currentUserId);

    /**
     * 上传应用文件。
     */
    AppFile uploadFile(String appId, String fileType, MultipartFile file, Long userId) throws IOException;

    /**
     * 下载文件。
     */
    AppFile getFile(String fileId);

    /**
     * 删除文件。
     */
    void deleteFile(String appId, String fileId, Long userId);

    /**
     * 获取应用历史版本列表。
     */
    List<AppVersion> getVersions(String appId);

    /**
     * 获取某版本详情。
     */
    AppVersion getVersionDetail(String appId, String versionId);

    /**
     * 获取文件下载路径。
     */
    String getFileDownloadPath(String fileId);
}
