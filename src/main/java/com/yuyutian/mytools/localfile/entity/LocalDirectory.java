package com.yuyutian.mytools.localfile.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 本地文件目录实体。
 *
 * @author mytools
 * @since 2026-05-10
 */
@Data
public class LocalDirectory {

    /** 主键 ID */
    private Long id;

    /** 目录名称 */
    private String directoryName;

    /** 目录路径 */
    private String directoryPath;

    /** 目录类型（MULTIMEDIA/EBOOK/LARGE_MEDIA） */
    private String directoryType;

    /** 是否启用扫描：0-禁用，1-启用 */
    private Integer scanEnabled;

    /** 最后扫描时间 */
    private LocalDateTime lastScanTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
