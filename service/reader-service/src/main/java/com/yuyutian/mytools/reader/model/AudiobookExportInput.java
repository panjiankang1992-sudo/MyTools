package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 后台执行器创建一份有声书归档所需的受控输入。
 *
 * @param exportId 导出标识
 * @param generationId 已冻结有声书版本标识
 * @param generationVersion 书籍内版本号
 * @param format 归档格式
 * @param chapters 已就绪章节音频清单
 */
public record AudiobookExportInput(UUID exportId, UUID generationId, int generationVersion,
                                   AudiobookExportFormat format, List<Chapter> chapters) {

    /**
     * 一条不可变章节音频资产定位信息。
     *
     * @param index 章节序号
     * @param title 章节标题
     * @param storageUri 仅供内部执行器读取的受管地址
     * @param contentSha256 音频摘要
     * @param sizeBytes 音频大小
     * @param format 音频格式
     */
    public record Chapter(int index, String title, String storageUri, String contentSha256, long sizeBytes,
                          String format) {
    }
}
