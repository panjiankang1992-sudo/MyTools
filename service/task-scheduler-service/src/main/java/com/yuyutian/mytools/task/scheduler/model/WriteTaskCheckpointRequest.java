package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * 写入任务检查点请求。
 *
 * @param requestId 稳定写请求标识
 * @param expectedVersion 预期当前版本，新建时为零
 * @param value 检查点内容
 */
public record WriteTaskCheckpointRequest(
        @NotNull UUID requestId,
        @Min(0) long expectedVersion,
        @NotNull Map<String, Object> value
) {
}
