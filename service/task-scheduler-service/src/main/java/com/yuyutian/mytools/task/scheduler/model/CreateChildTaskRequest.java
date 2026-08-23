package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * 脚本创建子任务请求。
 *
 * @param leaseToken 当前执行租约令牌
 * @param taskName 子任务名称
 * @param idempotencyKey 幂等键
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param priority 优先级
 * @param parameters 参数
 */
public record CreateChildTaskRequest(
        @NotNull UUID leaseToken,
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,127}$") String taskName,
        @NotBlank String idempotencyKey,
        String businessType,
        String businessId,
        @Min(0) @Max(100) int priority,
        @NotNull Map<String, Object> parameters
) {
}
