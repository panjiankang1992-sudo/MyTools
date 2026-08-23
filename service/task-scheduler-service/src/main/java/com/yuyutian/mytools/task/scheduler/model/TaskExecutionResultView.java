package com.yuyutian.mytools.task.scheduler.model;

import java.util.List;
import java.util.UUID;

/**
 * 任务实例及全部步骤执行结果视图。
 *
 * @param taskInstanceId 任务实例标识
 * @param status 任务状态
 * @param steps 步骤执行结果
 */
public record TaskExecutionResultView(
        UUID taskInstanceId,
        TaskStatus status,
        List<StepExecutionResultView> steps
) {
}
