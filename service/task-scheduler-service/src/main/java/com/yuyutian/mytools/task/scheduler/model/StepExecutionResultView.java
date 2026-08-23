package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 已上报脚本步骤结果视图。
 *
 * @param executionId 执行标识
 * @param nodeId 执行节点标识
 * @param executionMode 执行方式
 * @param targetIndex 多节点目标序号
 * @param targetCount 多节点目标总数
 * @param stepName 步骤名称
 * @param attempt 尝试次数
 * @param status 步骤状态
 * @param result 结构化结果
 * @param errorCode 错误码
 * @param errorMessage 错误摘要
 * @param finishedAt 完成时间
 */
public record StepExecutionResultView(
        UUID executionId,
        UUID nodeId,
        ExecutionMode executionMode,
        Integer targetIndex,
        Integer targetCount,
        String stepName,
        int attempt,
        TaskStatus status,
        Map<String, Object> result,
        String errorCode,
        String errorMessage,
        Instant finishedAt
) {
}
