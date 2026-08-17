package com.yuyutian.mytools.reader.model;

import lombok.Data;

/**
 * 用户阅读进度实体。
 */
@Data
public class ReadingProgress {
    private Long userId;
    private String bookId;
    private String chapterTitle;
    private Long locator;
    private Integer percentage;
    private Long clientUpdatedAt;
    private Long serverUpdatedAt;
    private boolean deleted;
    private Long revision;
}
