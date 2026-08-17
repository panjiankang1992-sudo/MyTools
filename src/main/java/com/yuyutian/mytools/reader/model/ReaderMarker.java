package com.yuyutian.mytools.reader.model;

import lombok.Data;

/**
 * 书签或批注同步实体。
 */
@Data
public class ReaderMarker {
    private Long userId;
    private String markerId;
    private String kind;
    private String bookId;
    private String chapterTitle;
    private Long locator;
    private String note;
    private Long createdAt;
    private Long clientUpdatedAt;
    private Long serverUpdatedAt;
    private boolean deleted;
    private Long revision;
}
