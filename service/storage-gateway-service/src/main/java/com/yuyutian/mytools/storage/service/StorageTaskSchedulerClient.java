package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.CreateTaskRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Storage Gateway 使用的任务调度客户端。
 */
@Component
public class StorageTaskSchedulerClient {
    private final com.yuyutian.mytools.task.client.TaskSchedulerClient client;

    /**
     * 创建任务调度客户端。
     *
     * @param builder REST 客户端构建器
     * @param schedulerUrl Scheduler 地址
     * @param businessToken 业务服务令牌
     * @param objectMapper JSON 映射器
     */
    @Autowired
    public StorageTaskSchedulerClient(RestClient.Builder builder,
                                      @Value("${storage.scheduler-url:http://127.0.0.1:23410}") String schedulerUrl,
                                      @Value("${storage.scheduler-token:}") String businessToken,
                                      ObjectMapper objectMapper) {
        this.client = new com.yuyutian.mytools.task.client.TaskSchedulerClient(
                builder.baseUrl(schedulerUrl).build(), objectMapper, businessToken,
                "storage-gateway-service");
    }

    /** 创建兼容测试使用的客户端。 @param builder REST 客户端构建器 @param schedulerUrl Scheduler 地址 @param businessToken 业务服务令牌 */
    public StorageTaskSchedulerClient(RestClient.Builder builder, String schedulerUrl, String businessToken) {
        this(builder, schedulerUrl, businessToken, new ObjectMapper().findAndRegisterModules());
    }

    /**
     * 创建不携带令牌的兼容客户端。
     *
     * @param builder REST 客户端构建器
     * @param schedulerUrl Scheduler 地址
     */
    public StorageTaskSchedulerClient(RestClient.Builder builder, String schedulerUrl) {
        this(builder, schedulerUrl, "", new ObjectMapper().findAndRegisterModules());
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
        return client.create(CreateTaskRequest.create(taskName, "storage:" + operation.idempotencyKey(),
                "STORAGE_OPERATION", operation.id().toString(), 40, parameters)).id();
    }

    /**
     * 幂等创建具备受管根挂载亲和约束的校验和任务。
     *
     * @param operation 校验和操作
     * @param root 受管根
     * @return 任务实例标识
     */
    public UUID createChecksumTask(ChecksumOperation operation, StorageRepository.ManagedRoot root) {
        return client.create(new CreateTaskRequest("storage_compute_checksum",
                "storage-checksum:" + operation.idempotencyKey(), "STORAGE_CHECKSUM_OPERATION",
                operation.id().toString(), 40, Map.of("checksumOperationId", operation.id().toString()),
                Map.of(root.nodeAffinityLabel(), root.nodeAffinityValue()))).id();
    }

    /**
     * 幂等创建远端移动恢复任务。
     *
     * @param operation 移动操作
     * @return 任务实例标识
     */
    public UUID createMoveRecoveryTask(StorageOperation operation) {
        return client.create(CreateTaskRequest.create("storage_recover_move",
                "storage-move-recovery:" + operation.id(), "STORAGE_MOVE_RECOVERY", operation.id().toString(),
                90, Map.of("operationId", operation.id().toString()))).id();
    }

    /**
     * 请求取消任务实例。
     *
     * @param taskId 任务实例标识
     */
    public void cancel(UUID taskId) {
        client.cancel(taskId);
    }
}
