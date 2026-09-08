package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 章节音频合成完成后的执行摘要。
 *
 * @param generationId 生成运行标识
 * @param synthesizedChapterCount 已合成章节数
 */
public record AudiobookSynthesisResult(UUID generationId, int synthesizedChapterCount) {
}
