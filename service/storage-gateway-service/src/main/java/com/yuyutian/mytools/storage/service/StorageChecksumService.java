package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ChecksumOperation;
import com.yuyutian.mytools.storage.model.CreateChecksumOperationRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.FinishChecksumOperationRequest;
import com.yuyutian.mytools.storage.model.StorageObject;
import com.yuyutian.mytools.storage.repository.StorageChecksumRepository;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 本地受管对象校验和任务编排服务。
 */
@Service
public class StorageChecksumService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final StorageChecksumRepository checksumRepository;
    private final StorageRepository storageRepository;
    private final StorageTaskSchedulerClient schedulerClient;
    private final StorageObjectService objectService;

    /**
     * 创建校验和任务服务。
     *
     * @param checksumRepository 校验和仓储
     * @param storageRepository 存储仓储
     * @param schedulerClient 调度客户端
     * @param objectService 对象读取服务
     */
    public StorageChecksumService(StorageChecksumRepository checksumRepository, StorageRepository storageRepository,
                                  StorageTaskSchedulerClient schedulerClient, StorageObjectService objectService) {
        this.checksumRepository = checksumRepository;
        this.storageRepository = storageRepository;
        this.schedulerClient = schedulerClient;
        this.objectService = objectService;
    }

    /**
     * 幂等创建并调度校验和操作。
     *
     * @param request 创建请求
     * @return 操作
     */
    public ChecksumOperation create(CreateChecksumOperationRequest request) {
        String relativePath = safeRelativePath(request.path());
        ChecksumOperation existing = checksumRepository.findByKey(request.idempotencyKey()).orElse(null);
        if (existing != null && (!existing.rootName().equals(request.rootName())
                || !existing.relativePath().equals(relativePath))) {
            throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
        }
        if (existing != null && existing.taskInstanceId() != null) {
            return existing;
        }
        StorageRepository.ManagedRoot root = storageRepository.findRoot(request.rootName())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.ROOT_NOT_FOUND.code()));
        Instant now = Instant.now();
        ChecksumOperation operation = existing == null
                ? new ChecksumOperation(UUID.randomUUID(), root.id(), root.name(), request.idempotencyKey(),
                relativePath, "CREATED", null, null, null, null, now, now)
                : existing;
        if (existing == null) {
            try {
                checksumRepository.insert(operation);
            } catch (DuplicateKeyException exception) {
                operation = checksumRepository.findByKey(request.idempotencyKey())
                        .orElseThrow(() -> exception);
                if (!operation.rootName().equals(request.rootName())
                        || !operation.relativePath().equals(relativePath)) {
                    throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code(), exception);
                }
            }
        }
        UUID taskId = schedulerClient.createChecksumTask(operation, root);
        checksumRepository.bindTask(operation.id(), taskId);
        return require(operation.id());
    }

    /**
     * 查询校验和操作。
     *
     * @param id 操作标识
     * @return 操作
     */
    public ChecksumOperation require(UUID id) {
        return checksumRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.CHECKSUM_NOT_FOUND.code()));
    }

    /**
     * 为绑定到正确挂载节点的执行器打开对象流。
     *
     * @param id 操作标识
     * @return 已验证对象
     */
    public StorageObject content(UUID id) {
        ChecksumOperation operation = require(id);
        if (!"RUNNING".equals(operation.status())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        return objectService.requireReadable(operation.rootName(), operation.relativePath());
    }

    /**
     * 幂等写入校验和操作终态。
     *
     * @param id 操作标识
     * @param request 完成请求
     * @return 最新操作
     */
    public ChecksumOperation finish(UUID id, FinishChecksumOperationRequest request) {
        if (!TERMINAL.contains(request.status()) || "SUCCEEDED".equals(request.status())
                && (request.sizeBytes() == null || request.contentSha256() == null)) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        ChecksumOperation current = require(id);
        if (TERMINAL.contains(current.status())) {
            if (!current.status().equals(request.status())
                    || "SUCCEEDED".equals(request.status()) && (!request.sizeBytes().equals(current.sizeBytes())
                    || !request.contentSha256().equals(current.contentSha256()))) {
                throw new IllegalStateException(ErrorCode.OPERATION_CONFLICT.code());
            }
            return current;
        }
        checksumRepository.finish(id, request.status(), request.sizeBytes(), request.contentSha256(),
                request.errorCode());
        return require(id);
    }

    private String safeRelativePath(String value) {
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")
                || path.getName(0).toString().equals(".mytools-staging")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        return path.toString();
    }
}
