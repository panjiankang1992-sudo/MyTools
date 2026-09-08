package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 已完成合成、可由内部播放通道读取的章节音频定位信息。
 *
 * @param generationId 生成运行标识
 * @param chapterIndex 章节序号
 * @param storageUri 受管音频位置，仅允许内部服务使用
 * @param sizeBytes 音频字节数
 * @param format 音频格式
 */
public record AudiobookAudioChapter(UUID generationId, int chapterIndex, String storageUri,
                                    long sizeBytes, String format) {
}
