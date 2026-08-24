package com.yuyutian.mytools.task.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MyTools 调用任务调度服务的统一网关。
 */
@Component
public class TaskSchedulerGateway {

    private final RestTemplate restTemplate;
    private final String schedulerUrl;

    /**
     * 创建任务调度网关。
     *
     * @param restTemplate HTTP 客户端
     * @param schedulerUrl 任务调度服务地址
     */
    public TaskSchedulerGateway(RestTemplate restTemplate,
                                @Value("${migration.tasks.scheduler-url:http://127.0.0.1:23210}")
                                String schedulerUrl) {
        this.restTemplate = restTemplate;
        this.schedulerUrl = schedulerUrl;
    }

    /**
     * 幂等创建一个任务实例。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 参数
     * @return 任务实例标识
     */
    public UUID create(String taskName, String idempotencyKey, String businessType, String businessId,
                       int priority, Map<String, Object> parameters) {
        return create(taskName, idempotencyKey, businessType, businessId, priority, parameters, Map.of());
    }

    /**
     * 幂等创建带节点标签约束的任务实例。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 参数
     * @param requiredNodeLabels 节点标签约束
     * @return 任务实例标识
     */
    public UUID create(String taskName, String idempotencyKey, String businessType, String businessId,
                       int priority, Map<String, Object> parameters,
                       Map<String, Object> requiredNodeLabels) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskName", taskName);
        request.put("idempotencyKey", idempotencyKey);
        request.put("businessType", businessType);
        request.put("businessId", businessId);
        request.put("parentTaskInstanceId", null);
        request.put("priority", priority);
        request.put("parameters", parameters);
        request.put("requiredNodeLabels", requiredNodeLabels);
        Map<?, ?> response = restTemplate.postForObject(
                normalizedSchedulerUrl() + "/api/v1/task-instances", request, Map.class);
        if (response == null || !(response.get("id") instanceof String id)) {
            throw new IllegalStateException("Task Scheduler response does not contain an instance ID");
        }
        return UUID.fromString(id);
    }

    private String normalizedSchedulerUrl() {
        return schedulerUrl.endsWith("/") ? schedulerUrl.substring(0, schedulerUrl.length() - 1) : schedulerUrl;
    }
}
