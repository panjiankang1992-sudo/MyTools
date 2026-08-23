package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/**
 * 创建任务实例请求。
 *
 * @param taskName 任务定义名称
 * @param idempotencyKey 幂等键
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param parentTaskInstanceId 父任务实例标识
 * @param priority 优先级
 * @param parameters 任务参数
 * @param requiredNodeLabels 执行节点必须满足的标签
 */
public record CreateTaskRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,127}$") String taskName,
        @NotBlank String idempotencyKey,
        String businessType,
        String businessId,
        UUID parentTaskInstanceId,
        @Min(0) @Max(100) int priority,
        @NotNull Map<String, Object> parameters,
        Map<String, Object> requiredNodeLabels
) {
    /**
     * 创建不指定节点标签约束的兼容请求。
     *
     * @param taskName 任务名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param parentTaskInstanceId 父任务标识
     * @param priority 优先级
     * @param parameters 任务参数
     */
    public CreateTaskRequest(String taskName, String idempotencyKey, String businessType, String businessId,
                             UUID parentTaskInstanceId, int priority, Map<String, Object> parameters) {
        this(taskName, idempotencyKey, businessType, businessId, parentTaskInstanceId, priority, parameters, Map.of());
    }
}
