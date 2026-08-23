package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.ReconciliationDigest;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 异步存储操作编排和执行回写服务。
 */
@Service
public class StorageOperationService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final StorageRepository repository;
    private final StorageTaskSchedulerClient schedulerClient;

    /**
     * 创建异步操作服务。
     *
     * @param repository 存储仓储
     * @param schedulerClient 调度客户端
     */
    public StorageOperationService(StorageRepository repository, StorageTaskSchedulerClient schedulerClient) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等创建并调度根扫描操作。
     *
     * @param request 创建请求
     * @return 已绑定任务的操作
     */
    public StorageOperation create(CreateOperationRequest request) {
        String sourcePath = normalizePath(request.sourcePath());
        StorageOperation existing = repository.findOperationByKey(request.idempotencyKey()).orElse(null);
        if (existing != null && !equivalent(existing, request, sourcePath)) {
            throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
        }
        if (existing != null && existing.taskInstanceId() != null) {
            return existing;
        }
        StorageProvider provider = repository.findProviderById(request.providerId())
                .filter(StorageProvider::enabled)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        Instant now = Instant.now();
        StorageOperation operation = existing == null
                ? new StorageOperation(UUID.randomUUID(), provider.id(), request.idempotencyKey(),
                request.operationType(), sourcePath, "CREATED", null, 0, request.maximumObjects(), null, now, now)
                : existing;
        if (existing == null) {
            repository.insertOperation(operation);
        }
        UUID taskId = schedulerClient.createScanTask(operation, operation.maximumObjects());
        repository.bindOperationTask(operation.id(), taskId);
        return require(operation.id());
    }

    /**
     * 查询异步操作。
     *
     * @param id 操作标识
     * @return 操作
     */
    public StorageOperation require(UUID id) {
        return repository.findOperationById(id)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.OPERATION_NOT_FOUND.code()));
    }

    /**
     * 幂等合并扫描对象批次。
     *
     * @param id 操作标识
     * @param items 对象批次
     * @return 最新操作
     */
    public StorageOperation mergeItems(UUID id, List<RemoteObjectView> items) {
        StorageOperation operation = require(id);
        if (!"RUNNING".equals(operation.status())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        repository.mergeOperationItems(id, items);
        return require(id);
    }

    /**
     * 幂等设置操作终态。
     *
     * @param id 操作标识
     * @param status 终态
     * @param errorCode 可选错误码
     * @return 最新操作
     */
    public StorageOperation finish(UUID id, String status, String errorCode) {
        if (!TERMINAL_STATUSES.contains(status)) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        StorageOperation operation = require(id);
        if (TERMINAL_STATUSES.contains(operation.status())) {
            if (!operation.status().equals(status)) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
            return operation;
        }
        repository.finishOperation(id, status, errorCode);
        return require(id);
    }

    /** 计算成功扫描快照的对账摘要。 @param id 操作标识 @return 摘要 */
    public ReconciliationDigest digest(UUID id) { return repository.operationDigest(id); }

    private boolean equivalent(StorageOperation operation, CreateOperationRequest request, String sourcePath) {
        return operation.providerId().equals(request.providerId())
                && operation.operationType().equals(request.operationType())
                && operation.sourcePath().equals(sourcePath)
                && operation.maximumObjects() == request.maximumObjects();
    }

    private String normalizePath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.length() > 2048 || path.startsWith("/") || path.contains(":") || path.contains("\\")
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        return path;
    }
}
