package com.yuyutian.mytools.reader.model;

/**
 * 电子书增量索引执行结果。
 *
 * @param indexed 成功写入数量
 * @param failed 解析失败数量
 * @param remaining 本批执行后剩余候选数量
 */
public record EbookIndexResult(int indexed, int failed, long remaining) {
}
