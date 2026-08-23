package com.yuyutian.mytools.reader.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 电子书确定性解析与模型补齐后的领域元数据。
 */
@Data
public class EbookMetadata {
    private Long localFileId;
    private String fileHash;
    private Integer metadataVersion;
    private String status;
    private String errorMessage;
    private Integer failureCount;
    private LocalDateTime retryAfter;
    private String title;
    private String author;
    private String description;
    private String language;
    private String category;
    private String completionStatus;
    private Integer chapterCount;
    private Long wordCount;
    private String coverPath;
    private String parserName;
    private String modelName;
    private String modelVersion;
    private LocalDateTime indexedAt;
}
