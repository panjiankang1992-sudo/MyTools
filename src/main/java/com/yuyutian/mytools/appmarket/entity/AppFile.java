package com.yuyutian.mytools.appmarket.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用文件表实体。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Data
@TableName("t_app_file")
public class AppFile {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String appId;

    private String versionId;

    private String fileType;

    private String fileName;

    private String filePath;

    private Long fileSize;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
