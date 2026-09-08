package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 执行器生成章节正文快照所需的受控输入。
 *
 * @param generationId 生成运行标识
 * @param sourceStorageUri 原始电子书受管存储地址
 * @param expectedBookSha256 原始电子书摘要
 * @param chapters 冻结的章节定位信息
 */
public record AudiobookTextProjectionInput(UUID generationId, String sourceStorageUri,
                                          String expectedBookSha256, List<Chapter> chapters) {

    /**
     * 一条冻结章节定位信息。
     *
     * @param index 章节序号
     * @param title 章节标题
     * @param resourceRef 格式相关资源引用
     * @param startOffset 可选起始字节偏移
     * @param endOffset 可选结束字节偏移
     */
    public record Chapter(int index, String title, String resourceRef, Long startOffset, Long endOffset) {
    }
}
