package com.yuyutian.mytools.appmarket.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用文件表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppFile {

    private String id;

    private String appId;

    private String versionId;

    private String fileType;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private LocalDateTime createdTime;
}
