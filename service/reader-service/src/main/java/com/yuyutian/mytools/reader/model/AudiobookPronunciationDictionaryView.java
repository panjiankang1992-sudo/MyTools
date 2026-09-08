package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 一代有声书中已冻结且可审核的读音词典。
 *
 * @param generationId 生成运行标识
 * @param fingerprintSha256 词典规范化摘要；空词典也使用稳定摘要
 * @param entries 已冻结词条
 */
public record AudiobookPronunciationDictionaryView(UUID generationId, String fingerprintSha256,
                                                   List<Entry> entries) {

    /**
     * 一条受控的中文词条与拼音读法。
     *
     * @param term 词条文本
     * @param pinyin 带声调数字的拼音
     */
    public record Entry(String term, String pinyin) {
    }
}
