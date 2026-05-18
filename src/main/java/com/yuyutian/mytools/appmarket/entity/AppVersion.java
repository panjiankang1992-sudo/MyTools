package com.yuyutian.mytools.appmarket.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用历史版本表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppVersion {

    private String id;

    private String appId;

    private String version;

    private String content;

    private String fileId;

    private LocalDateTime createdTime;
}
