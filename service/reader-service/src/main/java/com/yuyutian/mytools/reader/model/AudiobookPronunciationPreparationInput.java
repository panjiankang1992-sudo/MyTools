package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 读音修订执行器计算受影响章节所需的受控冻结输入。
 *
 * @param generationId 读音修订版本标识
 * @param term 需要精确匹配的中文词条
 * @param chapters 已冻结正文的章节清单
 */
public record AudiobookPronunciationPreparationInput(UUID generationId, String term, List<Chapter> chapters) {

    /**
     * 一个可由执行器校验并扫描的冻结章节快照。
     *
     * @param index 章节序号
     * @param contentSha256 已冻结正文摘要
     * @param textStorageUri 已冻结正文地址
     * @param textSizeBytes 已冻结正文字节数
     */
    public record Chapter(int index, String contentSha256, String textStorageUri, long textSizeBytes) {
    }
}
