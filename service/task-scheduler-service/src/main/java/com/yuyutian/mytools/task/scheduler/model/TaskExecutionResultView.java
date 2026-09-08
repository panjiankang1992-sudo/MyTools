package com.yuyutian.mytools.task.scheduler.model;

import java.util.List;
import java.util.UUID;

/**
 * 任务实例及全部步骤执行结果视图。
 *
 * @param taskInstanceId 任务实例标识
 * @param status 任务状态
 * @param compensationStatus 最近执行的补偿状态
 * @param compensationRequired 补偿失败是否需要人工处理
 * @param compensationErrorCode 补偿失败稳定错误码
 * @param steps 步骤执行结果
 */
public record TaskExecutionResultView(
        UUID taskInstanceId,
        TaskStatus status,
        CompensationStatus compensationStatus,
        boolean compensationRequired,
        String compensationErrorCode,
        List<StepExecutionResultView> steps
) {
}
