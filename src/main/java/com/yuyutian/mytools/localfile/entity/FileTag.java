package com.yuyutian.mytools.localfile.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文件标签实体。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class FileTag {

    /** 主键ID */
    private Long id;

    /** 文件ID */
    private Long fileId;

    /** 标签名称 */
    private String tagName;

    /** 标签类型 */
    private String tagType;

    /** 置信度 */
    private Double confidence;

    /** 打标签时间 */
    private LocalDateTime taggingTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
