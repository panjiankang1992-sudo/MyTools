package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 调度服务任务结果快照。
 *
 * @param taskInstanceId 任务标识
 * @param status 任务状态
 * @param steps 步骤尝试列表
 */
public record SchedulerResult(UUID taskInstanceId, String status, List<StepResult> steps) {
    /**
     * 单次步骤执行结果。
     *
     * @param executionId 执行标识
     * @param targetIndex 目标序号
     * @param targetCount 目标总数
     * @param stepName 步骤名称
     * @param attempt 尝试次数
     * @param status 执行状态
     * @param result 结果对象
     */
    public record StepResult(UUID executionId, Integer targetIndex, Integer targetCount, String stepName,
                             int attempt, String status, Map<String, Object> result) {
    }
}
