package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
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
@TableName("t_app_market")
public class AppMarket {

    @TableId(type = IdType.ASSIGN_ID)
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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
