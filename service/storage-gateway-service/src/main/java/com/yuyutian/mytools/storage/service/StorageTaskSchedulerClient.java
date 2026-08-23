package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.storage.model.ErrorCode;
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
     * 按操作类型幂等创建存储任务。
     *
     * @param operation 操作聚合
     * @return 任务实例标识
     */
    public UUID createOperationTask(StorageOperation operation) {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("operationId", operation.id().toString());
        if ("SCAN_ROOT".equals(operation.operationType())) {
            parameters.put("providerId", operation.providerId().toString());
            parameters.put("rootPath", operation.sourcePath());
            parameters.put("maximumObjects", operation.maximumObjects());
        }
        String taskName = switch (operation.operationType()) {
            case "SCAN_ROOT" -> "storage_scan_root";
            case "COPY_TREE" -> "storage_copy_tree";
            case "SYNC_REMOTE" -> "storage_sync_remote";
            default -> throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        };
        Map<String, Object> request = Map.of(
                "taskName", taskName,
                "idempotencyKey", "storage:" + operation.idempotencyKey(),
                "businessType", "STORAGE_OPERATION",
                "businessId", operation.id().toString(),
                "priority", 40,
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return UUID.fromString(response.path("id").asText());
    }
}
