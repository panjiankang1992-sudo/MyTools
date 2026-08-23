package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 完成任务执行请求。
 *
 * @param leaseToken 租约令牌
 * @param status 最终状态
 */
public record CompleteExecutionRequest(@NotNull UUID leaseToken, @NotNull TaskStatus status) {
}
