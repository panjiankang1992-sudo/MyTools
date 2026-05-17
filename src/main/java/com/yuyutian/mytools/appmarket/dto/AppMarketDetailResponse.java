package com.yuyutian.mytools.appmarket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 应用详情响应DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppMarketDetailResponse {

    private String id;

    private Long userId;

    private String userName;

    private String name;

    private String type;

    private String version;

    private String thumbnailId;

    private String thumbnailUrl;

    private String content;

    private String installCmd;

    private String downloadUrl;

    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;

    /** 当前版本文件ID（用于下载） */
    private String fileId;

    /** 当前文件名 */
    private String fileName;

    /** 当前文件大小 */
    private Long fileSize;

    /** 当前文件类型 */
    private String fileType;

    /** 缩略图文件路径 */
    private String thumbnailPath;

    /** 是否为所有者（前端权限判断用） */
    private Boolean isOwner;
}
