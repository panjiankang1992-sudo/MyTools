package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 阅读标记同步响应。
 */
@Data
@AllArgsConstructor
public class ReaderMarkerSyncResponse {
    private boolean accepted;
    private ReaderMarker marker;
}
