package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 书架同步响应。
 */
@Data
@AllArgsConstructor
public class ShelfBookSyncResponse {
    private boolean accepted;
    private ShelfBook book;
}
