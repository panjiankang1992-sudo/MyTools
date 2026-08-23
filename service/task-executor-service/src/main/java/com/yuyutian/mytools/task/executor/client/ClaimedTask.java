package com.yuyutian.mytools.task.executor.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 调度服务下发的任务执行租约。
 *
 * @param executionId 执行标识
 * @param taskInstanceId 任务实例标识
 * @param parentTaskInstanceId 父任务标识
 * @param taskName 任务名称
 * @param leaseToken 租约令牌
 * @param leaseUntil 租约截止时间
 * @param parameters 参数
 * @param steps 步骤
 */
public record ClaimedTask(
        UUID executionId,
        UUID taskInstanceId,
        UUID parentTaskInstanceId,
        String taskName,
        UUID leaseToken,
        Instant leaseUntil,
        Map<String, Object> parameters,
        List<ClaimedStep> steps
) {
}
