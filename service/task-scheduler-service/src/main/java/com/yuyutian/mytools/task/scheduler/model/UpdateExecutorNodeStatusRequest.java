package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新执行节点状态请求。
 *
 * @param status 目标状态
 * @param reason 操作原因
 * @param expectedInstanceId 预期的启动实例标识
 * @param expectedRunningTasks 切换状态时预期的运行任务数
 */
public record UpdateExecutorNodeStatusRequest(
        @NotNull NodeStatus status,
        @Size(max = 256) String reason,
        @NotBlank @Size(max = 256) String expectedInstanceId,
        @Min(0) Integer expectedRunningTasks
) {
    /**
     * 创建兼容未提供运行数前置条件的状态请求。
     */
    public UpdateExecutorNodeStatusRequest(NodeStatus status, String reason, String expectedInstanceId) {
        this(status, reason, expectedInstanceId, null);
    }
}
