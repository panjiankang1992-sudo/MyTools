package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.CreateOperationRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.RemoteObjectView;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.model.ReconciliationDigest;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import com.yuyutian.mytools.storage.repository.StorageMoveRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final RcloneRemoteConnector remoteConnector;
    private final StorageMoveRepository moveRepository;
    private final ProviderObjectConnectorRegistry connectorRegistry;

    /**
     * 创建异步操作服务。
     *
     * @param repository 存储仓储
     * @param schedulerClient 调度客户端
     * @param remoteConnector 远程存储连接器
     * @param moveRepository 移动状态仓储
     * @param connectorRegistry Provider 连接器注册表
     */
    public StorageOperationService(StorageRepository repository, StorageTaskSchedulerClient schedulerClient,
                                   RcloneRemoteConnector remoteConnector, StorageMoveRepository moveRepository,
                                   ProviderObjectConnectorRegistry connectorRegistry) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.remoteConnector = remoteConnector;
        this.moveRepository = moveRepository;
        this.connectorRegistry = connectorRegistry;
    }

    /**
     * 幂等创建并调度根扫描操作。
     *
     * @param request 创建请求
     * @return 已绑定任务的操作
     */
    public StorageOperation create(CreateOperationRequest request) {
        String sourcePath = normalizePath(request.sourcePath());
        String targetPath = normalizeTarget(request.operationType(), request.targetPath());
        UUID targetProviderId = targetProvider(request.operationType(), request.targetProviderId());
        boolean emptyTransferPath = ("MOVE_TREE".equals(request.operationType())
                || "COPY_OBJECT".equals(request.operationType()))
                && (sourcePath.isEmpty() || targetPath.isEmpty());
        boolean emptyDeletePath = "DELETE_TREE".equals(request.operationType()) && sourcePath.isEmpty();
        if (emptyTransferPath || emptyDeletePath) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        if (targetProviderId != null && request.providerId().equals(targetProviderId)
                && sourcePath.equals(targetPath)) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        StorageOperation existing = repository.findOperationByKey(request.idempotencyKey()).orElse(null);
        if (existing != null && !equivalent(existing, request, sourcePath, targetProviderId, targetPath)) {
            throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
        }
        if (existing != null && existing.taskInstanceId() != null) {
            return existing;
        }
        StorageProvider provider = repository.findProviderById(request.providerId())
                .filter(StorageProvider::enabled)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        StorageProvider target = null;
        if (targetProviderId != null) {
            target = repository.findProviderById(targetProviderId).filter(StorageProvider::enabled)
                    .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        }
        if (("COPY_OBJECT".equals(request.operationType()) || "COPY_TREE_NATIVE".equals(request.operationType()))
                && (!connectorRegistry.supportsContentRead(provider)
                || !connectorRegistry.supportsContentWrite(target))) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_UNSUPPORTED.code());
        }
        Instant now = Instant.now();
        StorageOperation operation = existing == null
                ? new StorageOperation(UUID.randomUUID(), provider.id(), request.idempotencyKey(),
                request.operationType(), sourcePath, targetProviderId, targetPath,
                "CREATED", null, null, 0, request.maximumObjects(), null, now, now)
                : existing;
        if (existing == null) {
            try {
                repository.insertOperation(operation);
            } catch (DuplicateKeyException exception) {
                operation = repository.findOperationByKey(request.idempotencyKey())
                        .orElseThrow(() -> exception);
                if (!equivalent(operation, request, sourcePath, targetProviderId, targetPath)) {
                    throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code(), exception);
                }
            }
        }
        if (operation.targetProviderId() != null && !"COPY_TREE_NATIVE".equals(operation.operationType())) {
            try {
                moveRepository.reserveTarget(operation.id(), operation.targetProviderId(), operation.targetPath(),
                        pathDigest(operation.targetPath()));
            } catch (IllegalStateException exception) {
                repository.markWaitingTarget(operation.id());
                throw exception;
            }
        }
        if ("MOVE_TREE".equals(operation.operationType())) {
            moveRepository.initialize(operation.id());
        }
        UUID taskId = schedulerClient.createOperationTask(operation);
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
     * 请求取消异步存储操作。
     *
     * @param id 操作标识
     * @return 当前操作
     */
    public StorageOperation cancel(UUID id) {
        StorageOperation operation = require(id);
        if ("COPY_TREE_NATIVE".equals(operation.operationType())) {
            cancelChildren(id);
        }
        if (!TERMINAL_STATUSES.contains(operation.status()) && operation.taskInstanceId() != null) {
            schedulerClient.cancel(operation.taskInstanceId());
        }
        return require(id);
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
        if ("COPY_TREE_NATIVE".equals(operation.operationType()) && "SUCCEEDED".equals(status)
                && repository.findChildOperations(id).stream().anyMatch(child -> !"SUCCEEDED".equals(child.status()))) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        if (TERMINAL_STATUSES.contains(operation.status())) {
            if (!operation.status().equals(status)) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
            if (operation.targetProviderId() != null) {
                moveRepository.releaseTarget(id);
            }
            return operation;
        }
        repository.finishOperation(id, status, errorCode);
        if (operation.targetProviderId() != null) {
            moveRepository.releaseTarget(id);
        }
        return require(id);
    }

    /**
     * 从父操作冻结清单中幂等创建单对象复制子操作。
     *
     * @param parentId 父操作标识
     * @param sourceObjectPath 来源对象路径
     * @return 已调度的子操作
     */
    public StorageOperation createNativeTreeChild(UUID parentId, String sourceObjectPath) {
        StorageOperation parent = require(parentId);
        if (!"COPY_TREE_NATIVE".equals(parent.operationType()) || !"RUNNING".equals(parent.status())
                || !repository.containsFrozenFile(parentId, sourceObjectPath)) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        String normalizedSource = normalizePath(sourceObjectPath);
        String relative = relativePath(parent.sourcePath(), normalizedSource);
        String childTarget = joinPath(parent.targetPath(), relative);
        String childKey = parent.idempotencyKey() + ":object:" + pathDigest(normalizedSource);
        StorageOperation child;
        try {
            child = create(new CreateOperationRequest(childKey, parent.providerId(), "COPY_OBJECT",
                    normalizedSource, parent.targetProviderId(), childTarget, 1));
        } catch (RuntimeException exception) {
            // 操作可能已落库但尚未完成目标预占或调度，仍需纳入父级补偿。
            repository.findOperationByKey(childKey).ifPresent(existing -> repository.linkChildOperation(
                    parentId, existing.id(), normalizedSource, childTarget));
            throw exception;
        }
        repository.linkChildOperation(parentId, child.id(), normalizedSource, childTarget);
        return child;
    }

    /**
     * 级联取消原生树复制子操作并设置父操作终态。
     *
     * @param id 父操作标识
     * @param status 父操作终态
     * @param errorCode 错误码
     * @return 最新父操作
     */
    public StorageOperation abortNativeTree(UUID id, String status, String errorCode) {
        StorageOperation operation = require(id);
        if (!"COPY_TREE_NATIVE".equals(operation.operationType())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        cancelChildren(id);
        return finish(id, status, errorCode);
    }

    private void cancelChildren(UUID parentId) {
        for (StorageOperation child : repository.findChildOperations(parentId)) {
            if (!TERMINAL_STATUSES.contains(child.status()) && child.taskInstanceId() != null) {
                schedulerClient.cancel(child.taskInstanceId());
            } else if (!TERMINAL_STATUSES.contains(child.status())) {
                finish(child.id(), "CANCELLED", "STORAGE_PARENT_ABORTED");
            }
        }
    }

    private String relativePath(String root, String objectPath) {
        if (root.isEmpty()) {
            return objectPath;
        }
        String prefix = root + "/";
        if (!objectPath.startsWith(prefix) || objectPath.length() == prefix.length()) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        return objectPath.substring(prefix.length());
    }

    private String joinPath(String root, String relative) {
        return root == null || root.isEmpty() ? relative : root + "/" + relative;
    }

    /** 计算成功扫描快照的对账摘要。 @param id 操作标识 @return 摘要 */
    public ReconciliationDigest digest(UUID id) { return repository.operationDigest(id); }

    /**
     * 幂等启动一个跨 Provider rclone 后台任务。
     *
     * @param id 操作标识
     * @return 最新操作
     */
    public StorageOperation startRemoteJob(UUID id) {
        StorageOperation operation = require(id);
        requireTransferShape(operation);
        if (operation.remoteJobId() != null) {
            return operation;
        }
        if (!"RUNNING".equals(operation.status())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        StorageProvider source = repository.findProviderById(operation.providerId())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        long jobId;
        if ("DELETE_TREE".equals(operation.operationType())) {
            // 删除目标完全由持久化操作定义，Executor 无法提交 Provider 或路径。
            jobId = remoteConnector.startPurge(source.remoteKey(), operation.sourcePath());
        } else {
            StorageProvider target = repository.findProviderById(operation.targetProviderId())
                    .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
            jobId = remoteConnector.startTransfer(operation.operationType(), source.remoteKey(),
                    operation.sourcePath(), target.remoteKey(), operation.targetPath());
        }
        repository.bindRemoteJob(operation.id(), jobId);
        return require(id);
    }

    /**
     * 查询并对账跨 Provider rclone 后台任务。
     *
     * @param id 操作标识
     * @return 远端任务状态
     */
    public RemoteJobView remoteJob(UUID id) {
        StorageOperation operation = require(id);
        requireTransferShape(operation);
        if (operation.remoteJobId() == null) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        RemoteJobView view = remoteConnector.jobStatus(operation.remoteJobId());
        if (view.finished() && !TERMINAL_STATUSES.contains(operation.status())) {
            finish(id, view.success() ? "SUCCEEDED" : "FAILED", view.errorCode());
        }
        return view;
    }

    /**
     * 停止跨 Provider 后台任务，供超时、失败和取消步骤调用。
     *
     * @param id 操作标识
     */
    public void stopRemoteJob(UUID id) {
        StorageOperation operation = require(id);
        if (operation.remoteJobId() != null && !TERMINAL_STATUSES.contains(operation.status())) {
            remoteConnector.stopJob(operation.remoteJobId());
        }
    }

    private boolean equivalent(StorageOperation operation, CreateOperationRequest request, String sourcePath,
                               UUID targetProviderId, String targetPath) {
        return operation.providerId().equals(request.providerId())
                && operation.operationType().equals(request.operationType())
                && operation.sourcePath().equals(sourcePath)
                && java.util.Objects.equals(operation.targetProviderId(), targetProviderId)
                && java.util.Objects.equals(operation.targetPath(), targetPath)
                && operation.maximumObjects() == request.maximumObjects();
    }

    private UUID targetProvider(String operationType, UUID targetProviderId) {
        if ("SCAN_ROOT".equals(operationType) || "DELETE_TREE".equals(operationType)) {
            if (targetProviderId != null) {
                throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
            }
            return null;
        }
        if (targetProviderId == null) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        return targetProviderId;
    }

    private String normalizeTarget(String operationType, String value) {
        if ("SCAN_ROOT".equals(operationType) || "DELETE_TREE".equals(operationType)) {
            if (value != null && !value.isBlank()) {
                throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
            }
            return null;
        }
        return normalizePath(value);
    }

    private void requireTransferShape(StorageOperation operation) {
        boolean transfer = ("COPY_TREE".equals(operation.operationType())
                || "SYNC_REMOTE".equals(operation.operationType())) && operation.targetProviderId() != null;
        boolean deletion = "DELETE_TREE".equals(operation.operationType())
                && operation.targetProviderId() == null;
        if (!transfer && !deletion) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
    }

    private String normalizePath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.length() > 2048 || path.startsWith("/") || path.contains(":") || path.contains("\\")
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        String normalized = java.nio.file.Path.of(path).normalize().toString();
        return ".".equals(normalized) ? "" : normalized;
    }

    private String pathDigest(String path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ErrorCode.IO_FAILURE.code(), exception);
        }
    }
}
