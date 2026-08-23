package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.storage.model.StorageOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Storage Gateway 使用的任务调度客户端。
 */
@Component
public class StorageTaskSchedulerClient {
    private final RestClient restClient;

    /**
     * 创建任务调度客户端。
     *
     * @param builder REST 客户端构建器
     * @param schedulerUrl Scheduler 地址
     */
    public StorageTaskSchedulerClient(RestClient.Builder builder,
                                      @Value("${storage.scheduler-url:http://127.0.0.1:23210}") String schedulerUrl) {
        this.restClient = builder.baseUrl(schedulerUrl).build();
    }

    /**
     * 幂等创建根扫描任务。
     *
     * @param operation 操作聚合
     * @param maximumObjects 最大对象数
     * @return 任务实例标识
     */
    public UUID createScanTask(StorageOperation operation, int maximumObjects) {
        Map<String, Object> parameters = Map.of(
                "operationId", operation.id().toString(),
                "providerId", operation.providerId().toString(),
                "rootPath", operation.sourcePath(),
                "maximumObjects", maximumObjects);
        Map<String, Object> request = Map.of(
                "taskName", "storage_scan_root",
                "idempotencyKey", "storage:" + operation.idempotencyKey(),
                "businessType", "STORAGE_OPERATION",
                "businessId", operation.id().toString(),
                "priority", 40,
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Scheduler returned an invalid task response");
        }
        return UUID.fromString(response.path("id").asText());
    }
}
