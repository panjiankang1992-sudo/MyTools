package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 执行租约续期请求。
 *
 * @param leaseToken 租约令牌
 * @param leaseSeconds 续期秒数
 */
public record LeaseHeartbeatRequest(@NotNull UUID leaseToken, @Min(10) @Max(3600) int leaseSeconds) {
}
