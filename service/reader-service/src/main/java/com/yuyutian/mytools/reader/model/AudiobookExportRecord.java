package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 有声书导出任务的持久化记录。
 *
 * @param id 导出标识
 * @param ownerId 所有者标识
 * @param generationId 已冻结有声书版本标识
 * @param idempotencyKey 调用方幂等键
 * @param format 归档格式
 * @param status 任务状态
 * @param currentStage 当前阶段
 * @param taskId 调度任务标识
 * @param chapterCount 归档章节数
 * @param errorCode 稳定错误码
 * @param createdAt 创建时间
 * @param startedAt 开始时间
 * @param finishedAt 完成时间
 * @param updatedAt 更新时间
 */
public record AudiobookExportRecord(UUID id, long ownerId, UUID generationId, String idempotencyKey,
                                    AudiobookExportFormat format, String status, String currentStage, UUID taskId,
                                    int chapterCount, String errorCode, Instant createdAt, Instant startedAt,
                                    Instant finishedAt, Instant updatedAt) {
}
