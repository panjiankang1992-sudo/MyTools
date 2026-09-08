package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新执行节点状态请求。
 *
 * @param status 目标状态
 * @param reason 操作原因
 */
public record UpdateExecutorNodeStatusRequest(
        @NotNull NodeStatus status,
        @Size(max = 256) String reason
) {
}
