package com.yuyutian.mytools.drive.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远端文件元数据索引。
 */
@Data
public class DriveItemIndex {
    private Long id;
    private Long driveId;
    private String remotePath;
    private String parentPath;
    private String displayName;
    private String mimeType;
    private String extension;
    private Boolean directory;
    private Long sizeBytes;
    private LocalDateTime modifiedAt;
    private String etag;
    private LocalDateTime indexedAt;
    private Boolean deleted;
}
