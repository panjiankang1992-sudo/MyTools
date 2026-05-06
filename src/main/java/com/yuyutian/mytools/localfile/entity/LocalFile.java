package com.yuyutian.mytools.localfile.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 本地文件实体。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Data
public class LocalFile {

    /** 主键ID */
    private Long id;

    /** 文件名 */
    private String filename;

    /** 文件路径 */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 文件类型（MIME type） */
    private String mimeType;

    /** 文件扩展名 */
    private String extension;

    /** 文件哈希值（SHA-256） */
    private String fileHash;

    /** 缩略图路径（图片/视频） */
    private String thumbnailPath;

    /** 标签状态：0-未打标签，1-已打标签，2-打标签失败 */
    private Integer taggingStatus;

    /** 扫描时间 */
    private LocalDateTime scanTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
