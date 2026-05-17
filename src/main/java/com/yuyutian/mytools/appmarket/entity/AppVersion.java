package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用历史版本表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@TableName("t_app_version")
public class AppVersion {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String appId;

    private String version;

    private String content;

    private String fileId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
