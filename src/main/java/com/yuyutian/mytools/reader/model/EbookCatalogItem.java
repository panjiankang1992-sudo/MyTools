package com.yuyutian.mytools.reader.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 电子书目录与详情统一返回模型。
 */
@Data
public class EbookCatalogItem {
    private Long localFileId;
    private String filename;
    private String filePath;
    private Long fileSize;
    private String extension;
    private String fileHash;
    private String title;
    private String author;
    private String description;
    private String language;
    private String category;
    private String completionStatus;
    private Integer chapterCount;
    private Long wordCount;
    @JsonIgnore
    private String coverPath;
    private Boolean coverAvailable;
    private String metadataStatus;
    private Integer adultStatus;
    private Boolean adultContent;
    private Double adultConfidence;
    private List<String> tags;
    private LocalDateTime updateTime;
}
