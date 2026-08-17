package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 书源同步响应。
 */
@Data
@AllArgsConstructor
public class BookSourceSyncResponse {
    private boolean accepted;
    private SyncedBookSource source;
}
