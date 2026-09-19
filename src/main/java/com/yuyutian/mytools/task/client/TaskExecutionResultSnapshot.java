package com.yuyutian.mytools.task.client;

import java.util.List;
import java.util.UUID;

/**
 * 统一任务网关返回的任务执行结果快照。
 *
 * @param taskInstanceId 任务实例标识
 * @param status 任务状态
 * @param steps 全部执行尝试结果
 */
public record TaskExecutionResultSnapshot(
        UUID taskInstanceId,
        String status,
        List<TaskStepResultSnapshot> steps
) {
}
