package com.yuyutian.mytools.reader.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 有声书生成运行的持久化记录。
 *
 * @param id 生成运行标识
 * @param ownerId 所有者标识
 * @param ebookAssetId 电子书资产标识
 * @param bookLineageKey 同一受管书籍的稳定谱系标识
 * @param idempotencyKey 幂等键
 * @param mode 生成范围
 * @param status 运行状态
 * @param currentStage 当前处理阶段
 * @param bookContentSha256 书籍正文摘要
 * @param generationVersion 同一本书的生成版本
 * @param taskId 正文投影调度任务标识
 * @param analysisTaskId 全书分析调度任务标识
 * @param voiceMatchTaskId 全书音色匹配调度任务标识
 * @param synthesisTaskId 章节合成调度任务标识
 * @param pronunciationTaskId 读音修订受影响章节计算任务标识
 * @param requestedChapterCount 请求章节数
 * @param completedChapterCount 已完成章节数
 * @param failedChapterCount 已失败章节数
 * @param errorCode 稳定错误码
 * @param createdAt 创建时间
 * @param startedAt 开始时间
 * @param finishedAt 完成时间
 * @param updatedAt 更新时间
 */
public record AudiobookGenerationRecord(UUID id, long ownerId, UUID ebookAssetId, String bookLineageKey,
                                        String idempotencyKey,
                                        AudiobookGenerationMode mode, String status, String currentStage,
                                        String bookContentSha256, int generationVersion, UUID taskId,
                                        UUID analysisTaskId, UUID voiceMatchTaskId, UUID synthesisTaskId,
                                        UUID pronunciationTaskId,
                                        int requestedChapterCount, int completedChapterCount,
                                        int failedChapterCount, String errorCode, Instant createdAt,
                                        Instant startedAt, Instant finishedAt, Instant updatedAt) {
}
