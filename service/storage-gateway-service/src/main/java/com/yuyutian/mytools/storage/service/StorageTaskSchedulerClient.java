package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.repository.StorageRepository;
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
                                      @Value("${storage.scheduler-url:http://127.0.0.1:23410}") String schedulerUrl) {
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
            case "COPY_OBJECT" -> "storage_copy_object";
            case "COPY_TREE" -> "storage_copy_tree";
            case "COPY_TREE_NATIVE" -> "storage_copy_tree_native";
            case "MOVE_TREE" -> "storage_move_tree";
            case "SYNC_REMOTE" -> "storage_sync_remote";
            case "DELETE_TREE" -> "storage_delete_tree";
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

    /**
     * 幂等创建具备受管根挂载亲和约束的校验和任务。
     *
     * @param operation 校验和操作
     * @param root 受管根
     * @return 任务实例标识
     */
    public UUID createChecksumTask(ChecksumOperation operation, StorageRepository.ManagedRoot root) {
        Map<String, Object> request = Map.of(
                "taskName", "storage_compute_checksum",
                "idempotencyKey", "storage-checksum:" + operation.idempotencyKey(),
                "businessType", "STORAGE_CHECKSUM_OPERATION",
                "businessId", operation.id().toString(),
                "priority", 40,
                "parameters", Map.of("checksumOperationId", operation.id().toString()),
                "requiredNodeLabels", Map.of(root.nodeAffinityLabel(), root.nodeAffinityValue()));
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 幂等创建远端移动恢复任务。
     *
     * @param operation 移动操作
     * @return 任务实例标识
     */
    public UUID createMoveRecoveryTask(StorageOperation operation) {
        Map<String, Object> request = Map.of(
                "taskName", "storage_recover_move",
                "idempotencyKey", "storage-move-recovery:" + operation.id(),
                "businessType", "STORAGE_MOVE_RECOVERY",
                "businessId", operation.id().toString(),
                "priority", 90,
                "parameters", Map.of("operationId", operation.id().toString()));
        JsonNode response = restClient.post().uri("/api/v1/task-instances")
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException(ErrorCode.REMOTE_FAILURE.code());
        }
        return UUID.fromString(response.path("id").asText());
    }

    /**
     * 请求取消任务实例。
     *
     * @param taskId 任务实例标识
     */
    public void cancel(UUID taskId) {
        restClient.post().uri("/api/v1/task-instances/{id}/cancel", taskId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity();
    }
}
