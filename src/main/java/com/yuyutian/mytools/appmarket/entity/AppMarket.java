package com.yuyutian.mytools.appmarket.entity;

import com.yuyutian.mytools.appmarket.enums.AppStatus;
import com.yuyutian.mytools.appmarket.enums.AppType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用市场主表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
public class AppMarket {

    private String id;

    private Long userId;

    private String name;

    private AppType type;

    private String version;

    private String thumbnailId;

    private String content;

    private String installCmd;

    private String downloadUrl;

    private AppStatus status;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
