package com.yuyutian.mytools.task.scheduler.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 已上报脚本步骤结果视图。
 *
 * @param executionId 执行标识
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
        String stepName,
        int attempt,
        TaskStatus status,
        Map<String, Object> result,
        String errorCode,
        String errorMessage,
        Instant finishedAt
) {
}
