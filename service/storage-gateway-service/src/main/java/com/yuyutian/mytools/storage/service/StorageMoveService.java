package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.MoveProgress;
import com.yuyutian.mytools.storage.model.RemoteJobView;
import com.yuyutian.mytools.storage.model.StorageMoveState;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageMoveRepository;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 远端目录复制、下载校验、源清理和失败补偿状态机。
 */
@Service
public class StorageMoveService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private final StorageOperationService operationService;
    private final StorageRepository storageRepository;
    private final StorageMoveRepository moveRepository;
    private final RcloneRemoteConnector connector;
    private final StorageTaskSchedulerClient schedulerClient;

    /**
     * 创建远端移动服务。
     *
     * @param operationService 操作服务
     * @param storageRepository 存储仓储
     * @param moveRepository 移动状态仓储
     * @param connector rclone 连接器
     * @param schedulerClient 调度客户端
     */
    public StorageMoveService(StorageOperationService operationService, StorageRepository storageRepository,
                              StorageMoveRepository moveRepository, RcloneRemoteConnector connector,
                              StorageTaskSchedulerClient schedulerClient) {
        this.operationService = operationService;
        this.storageRepository = storageRepository;
        this.moveRepository = moveRepository;
        this.connector = connector;
        this.schedulerClient = schedulerClient;
    }

    /**
     * 幂等推进一次移动状态机。
     *
     * @param operationId 操作标识
     * @return 最新进度
     */
    public MoveProgress advance(UUID operationId) {
        MoveContext context = context(operationId);
        StorageMoveState state = context.state();
        return switch (state.phase()) {
            case "READY" -> startCopy(context);
            case "COPYING" -> pollCopy(context);
            case "VERIFYING" -> pollVerification(context);
            case "DELETING", "DELETE_RETRY" -> pollOrRetryDelete(context);
            case "COMPENSATING", "COMPENSATION_RETRY" -> pollOrRetryCompensation(context);
            case "TERMINAL" -> terminalProgress(context.operation(), state);
            case "RECOVERY_REQUIRED", "RECOVERING", "RECOVERED" -> recoveryProgress(state);
            default -> throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        };
    }

    /**
     * 请求中止移动；源删除前回滚目标，源删除开始后继续前向收敛。
     *
     * @param operationId 操作标识
     * @param status 失败、超时或取消终态
     * @return 最新进度
     */
    public MoveProgress abort(UUID operationId, String status) {
        if (!("FAILED".equals(status) || "TIMED_OUT".equals(status) || "CANCELLED".equals(status))) {
            throw new IllegalArgumentException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        MoveContext context = context(operationId);
        StorageMoveState state = context.state();
        if ("DELETING".equals(state.phase()) || "DELETE_RETRY".equals(state.phase())) {
            // 来源删除已经开始后忽略取消终态，继续完成已验证目标的前向收敛。
            return advance(operationId);
        }
        moveRepository.requestAbort(operationId, status);
        context = context(operationId);
        state = context.state();
        if ("READY".equals(state.phase())) {
            moveRepository.transition(operationId, "READY", "TERMINAL", null, null);
            moveRepository.releaseTarget(operationId);
            return terminalProgress(context.operation(), moveRepository.require(operationId));
        }
        if ("COPYING".equals(state.phase()) || "VERIFYING".equals(state.phase())) {
            connector.stopJob(state.remoteJobId() == null ? 0 : state.remoteJobId());
            return startCompensation(context, state.phase(), "STORAGE_MOVE_ABORTED");
        }
        return advance(operationId);
    }

    /**
     * 在特殊步骤截止前仍无法收敛时记录恢复标记。
     *
     * @param operationId 操作标识
     * @return 恢复状态
     */
    public MoveProgress markRecoveryRequired(UUID operationId) {
        StorageOperation operation = requireMove(operationId);
        StorageMoveState current = moveRepository.require(operationId);
        String action = current.recoveryAction() != null ? current.recoveryAction()
                : ("DELETING".equals(current.phase()) || "DELETE_RETRY".equals(current.phase())
                ? "PURGE_SOURCE" : "PURGE_TARGET");
        moveRepository.markRecoveryRequired(operationId, ErrorCode.MOVE_RECOVERY_REQUIRED.code(), action);
        if (!TERMINAL_STATUSES.contains(operation.status())) {
            // 恢复动作完成前保留目标写入栅栏，避免其他复制任务覆盖待补偿路径。
            storageRepository.finishOperation(operationId, "FAILED", ErrorCode.MOVE_RECOVERY_REQUIRED.code());
        }
        schedulerClient.createMoveRecoveryTask(operation);
        return recoveryProgress(moveRepository.require(operationId));
    }

    /**
     * 推进一个持久化恢复动作。
     *
     * @param operationId 操作标识
     * @return 恢复进度
     */
    public MoveProgress recover(UUID operationId) {
        MoveContext context = context(operationId);
        StorageMoveState state = context.state();
        if ("RECOVERED".equals(state.phase())) {
            return new MoveProgress("RECOVERED", true, true, false, state.failureCode());
        }
        if ("RECOVERY_REQUIRED".equals(state.phase())) {
            String remoteKey = "PURGE_SOURCE".equals(state.recoveryAction())
                    ? context.source().remoteKey() : context.target().remoteKey();
            String path = "PURGE_SOURCE".equals(state.recoveryAction())
                    ? context.operation().sourcePath() : context.operation().targetPath();
            long jobId = connector.startPurge(remoteKey, path);
            moveRepository.startRecovery(operationId, jobId);
            return progress(moveRepository.require(operationId));
        }
        if (!"RECOVERING".equals(state.phase())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        RemoteJobView job = connector.jobStatus(requiredJob(state));
        if (!job.finished()) {
            return progress(state);
        }
        if (!job.success()) {
            moveRepository.retryRecovery(operationId);
            return progress(moveRepository.require(operationId));
        }
        moveRepository.completeRecovery(operationId);
        moveRepository.releaseTarget(operationId);
        return new MoveProgress("RECOVERED", true, true, false, state.failureCode());
    }

    private MoveProgress startCopy(MoveContext context) {
        if (connector.exists(context.target().remoteKey(), context.operation().targetPath())) {
            moveRepository.transition(context.operation().id(), "READY", "TERMINAL", null,
                    ErrorCode.TARGET_CONFLICT.code());
            moveRepository.releaseTarget(context.operation().id());
            return terminalProgress(context.operation(), moveRepository.require(context.operation().id()));
        }
        long jobId = connector.startTransfer("COPY_TREE", context.source().remoteKey(),
                context.operation().sourcePath(), context.target().remoteKey(), context.operation().targetPath());
        moveRepository.transition(context.operation().id(), "READY", "COPYING", jobId, null);
        return progress(moveRepository.require(context.operation().id()));
    }

    private MoveProgress pollCopy(MoveContext context) {
        RemoteJobView job = connector.jobStatus(requiredJob(context.state()));
        if (!job.finished()) {
            return progress(context.state());
        }
        if (!job.success()) {
            return startCompensation(context, "COPYING", ErrorCode.REMOTE_FAILURE.code());
        }
        long verificationId = connector.startVerification(context.source().remoteKey(),
                context.operation().sourcePath(), context.target().remoteKey(), context.operation().targetPath());
        moveRepository.transition(context.operation().id(), "COPYING", "VERIFYING", verificationId, null);
        return progress(moveRepository.require(context.operation().id()));
    }

    private MoveProgress pollVerification(MoveContext context) {
        RemoteJobView job = connector.verificationJobStatus(requiredJob(context.state()));
        if (!job.finished()) {
            return progress(context.state());
        }
        if (!job.success()) {
            return startCompensation(context, "VERIFYING", ErrorCode.MOVE_VERIFICATION_FAILED.code());
        }
        long deleteId = connector.startPurge(context.source().remoteKey(), context.operation().sourcePath());
        moveRepository.transition(context.operation().id(), "VERIFYING", "DELETING", deleteId, null);
        return progress(moveRepository.require(context.operation().id()));
    }

    private MoveProgress pollOrRetryDelete(MoveContext context) {
        if ("DELETE_RETRY".equals(context.state().phase())) {
            long deleteId = connector.startPurge(context.source().remoteKey(), context.operation().sourcePath());
            moveRepository.transition(context.operation().id(), "DELETE_RETRY", "DELETING", deleteId,
                    ErrorCode.MOVE_SOURCE_DELETE_FAILED.code());
            return progress(moveRepository.require(context.operation().id()));
        }
        RemoteJobView job = connector.jobStatus(requiredJob(context.state()));
        if (!job.finished()) {
            return progress(context.state());
        }
        if (!job.success()) {
            moveRepository.transition(context.operation().id(), "DELETING", "DELETE_RETRY", null,
                    ErrorCode.MOVE_SOURCE_DELETE_FAILED.code());
            return progress(moveRepository.require(context.operation().id()));
        }
        moveRepository.clearFailure(context.operation().id());
        moveRepository.transition(context.operation().id(), "DELETING", "TERMINAL", null, null);
        moveRepository.releaseTarget(context.operation().id());
        return terminalProgress(context.operation(), moveRepository.require(context.operation().id()));
    }

    private MoveProgress startCompensation(MoveContext context, String expectedPhase, String failureCode) {
        try {
            long purgeId = connector.startPurge(context.target().remoteKey(), context.operation().targetPath());
            moveRepository.transition(context.operation().id(), expectedPhase, "COMPENSATING", purgeId, failureCode);
            return progress(moveRepository.require(context.operation().id()));
        } catch (RuntimeException exception) {
            return markRecoveryRequired(context.operation().id());
        }
    }

    private MoveProgress pollOrRetryCompensation(MoveContext context) {
        if ("COMPENSATION_RETRY".equals(context.state().phase())) {
            return startCompensation(context, "COMPENSATION_RETRY", context.state().failureCode());
        }
        RemoteJobView job = connector.jobStatus(requiredJob(context.state()));
        if (!job.finished()) {
            return progress(context.state());
        }
        if (!job.success()) {
            moveRepository.transition(context.operation().id(), "COMPENSATING", "COMPENSATION_RETRY", null,
                    ErrorCode.MOVE_COMPENSATION_FAILED.code());
            return progress(moveRepository.require(context.operation().id()));
        }
        moveRepository.transition(context.operation().id(), "COMPENSATING", "TERMINAL", null, null);
        moveRepository.releaseTarget(context.operation().id());
        return terminalProgress(context.operation(), moveRepository.require(context.operation().id()));
    }

    private MoveProgress terminalProgress(StorageOperation operation, StorageMoveState state) {
        String status = state.desiredTerminalStatus();
        if (status == null) {
            status = state.failureCode() == null ? "SUCCEEDED" : "FAILED";
        }
        if (!TERMINAL_STATUSES.contains(operation.status())) {
            operationService.finish(operation.id(), status, state.failureCode());
        }
        return new MoveProgress("TERMINAL", true, "SUCCEEDED".equals(status), false, state.failureCode());
    }

    private MoveProgress recoveryProgress(StorageMoveState state) {
        if ("RECOVERED".equals(state.phase())) {
            return new MoveProgress("RECOVERED", true, true, false, state.failureCode());
        }
        if ("RECOVERING".equals(state.phase())) {
            return new MoveProgress("RECOVERING", false, false, true, state.failureCode());
        }
        return new MoveProgress("RECOVERY_REQUIRED", true, false, true, state.failureCode());
    }

    private MoveProgress progress(StorageMoveState state) {
        return new MoveProgress(state.phase(), false, false, state.recoveryRequired(), state.failureCode());
    }

    private long requiredJob(StorageMoveState state) {
        if (state.remoteJobId() == null || state.remoteJobId() <= 0) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        return state.remoteJobId();
    }

    private MoveContext context(UUID operationId) {
        StorageOperation operation = requireMove(operationId);
        StorageProvider source = storageRepository.findProviderById(operation.providerId())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        StorageProvider target = storageRepository.findProviderById(operation.targetProviderId())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
        return new MoveContext(operation, moveRepository.require(operationId), source, target);
    }

    private StorageOperation requireMove(UUID operationId) {
        StorageOperation operation = operationService.require(operationId);
        if (!"MOVE_TREE".equals(operation.operationType())) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        return operation;
    }

    private record MoveContext(StorageOperation operation, StorageMoveState state,
                               StorageProvider source, StorageProvider target) {
    }
}
