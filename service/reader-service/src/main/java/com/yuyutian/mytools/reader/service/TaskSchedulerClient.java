package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 任务调度服务 HTTP 客户端。
 */
public class TaskSchedulerClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 创建任务调度客户端。
     *
     * @param restClient HTTP 客户端
     * @param objectMapper JSON 转换器
     */
    public TaskSchedulerClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等创建书源搜索任务。
     *
     * @param idempotencyKey 调度幂等键
     * @param businessId 搜索请求标识
     * @param parameters 脚本参数
     * @return 任务实例标识
     */
    public UUID createSearchTask(String idempotencyKey, UUID businessId, Map<String, Object> parameters) {
        return createTask("reader_source_search", idempotencyKey, "READER_SEARCH", businessId, 40, parameters);
    }

    /**
     * 幂等创建指定类型任务。
     *
     * @param taskName 任务定义名称
     * @param idempotencyKey 调度幂等键
     * @param businessType 业务类型
     * @param businessId 业务标识
     * @param priority 优先级
     * @param parameters 脚本参数
     * @return 任务实例标识
     */
    public UUID createTask(String taskName, String idempotencyKey, String businessType, UUID businessId,
                           int priority, Map<String, Object> parameters) {
        Map<String, Object> request = Map.of(
                "taskName", taskName,
                "idempotencyKey", idempotencyKey,
                "businessType", businessType,
                "businessId", businessId.toString(),
                "priority", priority,
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 查询任务及其全部执行结果。
     *
     * @param taskId 任务标识
     * @return 调度结果
     */
    public SchedulerResult getResults(UUID taskId) {
        JsonNode response = restClient.get().uri("/api/v1/task-instances/{id}/results", taskId)
                .retrieve().body(JsonNode.class);
        return objectMapper.convertValue(response, SchedulerResult.class);
    }

    /**
     * 取消任务执行。
     *
     * @param taskId 任务标识
     */
    public void cancel(UUID taskId) {
        restClient.post().uri("/api/v1/task-instances/{id}/cancel", taskId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
    }
}
