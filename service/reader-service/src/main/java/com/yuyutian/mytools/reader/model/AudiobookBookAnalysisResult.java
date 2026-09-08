package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 全书结构化分析完成后的执行摘要。
 *
 * @param generationId 生成运行标识
 * @param characterCount 已归并角色数
 * @param relationshipCount 已保存关系数
 * @param speechSegmentCount 已保存说话人片段数
 */
public record AudiobookBookAnalysisResult(UUID generationId, int characterCount, int relationshipCount,
                                          int speechSegmentCount) {
}
