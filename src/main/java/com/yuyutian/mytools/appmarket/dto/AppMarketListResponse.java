package com.yuyutian.mytools.appmarket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 应用列表响应DTO。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppMarketListResponse {

    private String id;

    private String name;

    private String type;

    private String version;

    private String thumbnailId;

    private String thumbnailUrl;

    private String contentPreview;

    private String status;

    private Long userId;

    private String userName;

    private LocalDateTime createdTime;

    private LocalDateTime updateTime;
}
