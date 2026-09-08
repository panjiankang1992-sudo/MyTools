package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 面向调用方的有声书生成运行视图。
 *
 * @param id 生成运行标识
 * @param status 运行状态
 * @param currentStage 当前处理阶段
 * @param mode 生成范围
 * @param generationVersion 生成版本
 * @param requestedChapterCount 请求章节数
 * @param completedChapterCount 已完成章节数
 * @param failedChapterCount 已失败章节数
 * @param errorCode 稳定错误码
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AudiobookGenerationView(UUID id, String status, String currentStage,
                                      AudiobookGenerationMode mode, int generationVersion,
                                      int requestedChapterCount, int completedChapterCount,
                                      int failedChapterCount, String errorCode,
                                      Instant createdAt, Instant updatedAt) {
}
