package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 阅读进度同步响应。
 */
@Data
@AllArgsConstructor
public class ReadingProgressSyncResponse {
    private boolean accepted;
    private ReadingProgress progress;
}
