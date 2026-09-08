package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 面向调用方的有声书导出任务摘要，不含底层存储地址。
 *
 * @param id 导出标识
 * @param generationId 有声书版本标识
 * @param format 归档格式
 * @param status 任务状态
 * @param currentStage 当前阶段
 * @param chapterCount 归档章节数
 * @param sizeBytes 完成归档大小；未完成时为空
 * @param errorCode 稳定错误码；无错误时为空
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AudiobookExportView(UUID id, UUID generationId, AudiobookExportFormat format, String status,
                                  String currentStage, int chapterCount, Long sizeBytes, String errorCode,
                                  Instant createdAt, Instant updatedAt) {
}
