package com.yuyutian.mytools.task.client;

import java.util.Map;

/**
 * 创建任务实例请求。
 *
 * @param taskName 任务定义名称
 * @param idempotencyKey 幂等键
 * @param businessType 业务类型
 * @param businessId 业务标识
 * @param priority 优先级
 * @param parameters 参数
 * @param requiredNodeLabels 节点标签约束
 */
public record CreateTaskRequest(String taskName, String idempotencyKey, String businessType, String businessId,
                                int priority, Map<String, Object> parameters,
                                Map<String, Object> requiredNodeLabels) {

    /**
     * 创建无节点标签约束的请求。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 参数
     * @return 创建请求
     */
    public static CreateTaskRequest create(String taskName, String idempotencyKey, String businessType,
                                            String businessId, int priority, Map<String, Object> parameters) {
        return new CreateTaskRequest(taskName, idempotencyKey, businessType, businessId, priority,
                Map.copyOf(parameters), Map.of());
    }
}
