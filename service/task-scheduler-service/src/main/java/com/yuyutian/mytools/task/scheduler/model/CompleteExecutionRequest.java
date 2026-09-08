package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 完成任务执行请求。
 *
 * @param completionRequestId 稳定完成请求标识
 * @param leaseToken 租约令牌
 * @param status 最终状态
 * @param compensationStatus 补偿状态
 * @param compensationRequired 补偿失败是否需要人工处理
 * @param compensationErrorCode 补偿失败稳定错误码
 */
public record CompleteExecutionRequest(@NotNull UUID completionRequestId, @NotNull UUID leaseToken,
                                       @NotNull TaskStatus status,
                                       CompensationStatus compensationStatus,
                                       boolean compensationRequired,
                                       @Size(max = 128) String compensationErrorCode) {
    /**
     * 创建兼容旧 Java 调用方的完成请求。
     *
     * @param leaseToken 租约令牌
     * @param status 最终状态
     */
    public CompleteExecutionRequest(UUID leaseToken, TaskStatus status) {
        this(UUID.randomUUID(), leaseToken, status, CompensationStatus.NOT_REQUIRED, false, null);
    }

    /**
     * 创建兼容旧协议调用方的完成请求。
     *
     * @param completionRequestId 稳定完成请求标识
     * @param leaseToken 租约令牌
     * @param status 最终状态
     */
    public CompleteExecutionRequest(UUID completionRequestId, UUID leaseToken, TaskStatus status) {
        this(completionRequestId, leaseToken, status, CompensationStatus.NOT_REQUIRED, false, null);
    }

    /**
     * 将缺省补偿状态规范化为无需补偿。
     *
     * @return 规范化补偿状态
     */
    public CompensationStatus normalizedCompensationStatus() {
        return compensationStatus == null ? CompensationStatus.NOT_REQUIRED : compensationStatus;
    }
}
