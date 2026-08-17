package com.yuyutian.mytools.reader.model;

import lombok.Data;

/**
 * 跨设备书源快照实体。
 */
@Data
public class SyncedBookSource {
    private Long userId;
    private String syncKey;
    private String sourceUrl;
    private String snapshotJson;
    private Long clientUpdatedAt;
    private Long serverUpdatedAt;
    private boolean deleted;
    private Long revision;
}
