package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 正文快照冻结完成后的执行摘要。
 *
 * @param generationId 生成运行标识
 * @param projectedChapterCount 已冻结章节数
 * @param projectedCharacterCount 已冻结正文的 Unicode 码点数
 */
public record AudiobookTextProjectionResult(UUID generationId, int projectedChapterCount,
                                            long projectedCharacterCount) {
}
