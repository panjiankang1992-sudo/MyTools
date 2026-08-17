package com.yuyutian.mytools.reader.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 云端阅读数据删除结果。
 */
@Data
@AllArgsConstructor
public class ReaderDataDeleteResponse {
    private long deletedRecords;
}
