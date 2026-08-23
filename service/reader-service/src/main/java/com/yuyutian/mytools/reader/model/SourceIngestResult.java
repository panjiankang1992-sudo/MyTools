package com.yuyutian.mytools.reader.model;

/**
 * 书源批量写入结果。
 *
 * @param saved 保存或确认未变化的数量
 * @param rejected 无效数量
 */
public record SourceIngestResult(int saved, int rejected) {
}
