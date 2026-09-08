package com.yuyutian.mytools.task.client;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 任务步骤结果公共快照。
 *
 * @param executionId 执行标识
 * @param nodeId 节点标识
 * @param executionMode 执行模式
 * @param targetIndex 目标序号
 * @param targetCount 目标总数
 * @param stepName 步骤名称
 * @param attempt 尝试次数
 * @param status 状态
 * @param result 输出
 * @param errorCode 错误码
 * @param errorMessage 错误信息
 * @param finishedAt 完成时间
 */
public record TaskStepResultSnapshot(UUID executionId, UUID nodeId, String executionMode,
                                     Integer targetIndex, Integer targetCount, String stepName,
                                     int attempt, String status, Map<String, Object> result,
                                     String errorCode, String errorMessage, Instant finishedAt) {
}
