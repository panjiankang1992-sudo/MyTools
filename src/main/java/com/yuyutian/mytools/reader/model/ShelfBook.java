package com.yuyutian.mytools.reader.model;

import lombok.Data;

/**
 * 跨设备书架元数据实体。
 */
@Data
public class ShelfBook {
    private Long userId;
    private String syncKey;
    private String bookId;
    private String name;
    private String author;
    private String origin;
    private String format;
    private String resourceUri;
    private String sourceId;
    private String remoteCoverUrl;
    private Long clientUpdatedAt;
    private Long serverUpdatedAt;
    private boolean deleted;
    private Long revision;
}
