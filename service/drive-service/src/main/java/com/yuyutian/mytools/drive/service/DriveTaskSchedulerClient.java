package com.yuyutian.mytools.drive.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Drive 使用的任务调度客户端。
 */
public class DriveTaskSchedulerClient {
    private final RestClient restClient;

    /**
     * 创建客户端。
     *
     * @param restClient HTTP 客户端
     */
    public DriveTaskSchedulerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 创建账户索引任务。
     *
     * @param operationId 操作标识
     * @param accountId 账户标识
     * @param idempotencyKey 幂等键
     * @return 任务标识
     */
    public UUID createIndexTask(UUID operationId, UUID accountId, String idempotencyKey) {
        Map<String, Object> request = Map.of(
            "taskName", "drive_index_account",
            "idempotencyKey", "drive_index:" + idempotencyKey,
            "businessType", "DRIVE_INDEX",
            "businessId", operationId.toString(),
            "priority", 40,
            "parameters", Map.of("accountId", accountId.toString()));
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
            .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 查询任务状态。
     *
     * @param taskId 任务标识
     * @return 任务状态
     */
    public String getStatus(UUID taskId) {
        JsonNode response = restClient.get().uri("/api/v1/task-instances/{id}", taskId)
            .retrieve().body(JsonNode.class);
        if (response == null || response.path("status").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return response.path("status").asText();
    }

    /**
     * 取消任务。
     *
     * @param taskId 任务标识
     */
    public void cancel(UUID taskId) {
        restClient.post().uri("/api/v1/task-instances/{id}/cancel", taskId)
            .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
    }
}
