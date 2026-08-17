package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前用户云端阅读数据摘要。
 */
@Data
@AllArgsConstructor
public class ReaderDataSummary {
    private long shelfRecords;
    private long sourceRecords;
    private long progressRecords;
    private long markerRecords;
}
