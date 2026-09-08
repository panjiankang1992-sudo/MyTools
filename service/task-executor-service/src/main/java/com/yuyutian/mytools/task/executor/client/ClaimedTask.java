package com.yuyutian.mytools.task.executor.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 调度服务下发的任务执行租约。
 *
 * @param executionId 执行标识
 * @param taskInstanceId 任务实例标识
 * @param parentTaskInstanceId 父任务标识
 * @param taskName 任务名称
 * @param definitionId 任务定义标识
 * @param definitionVersion 任务定义版本
 * @param definitionDigest 执行契约摘要
 * @param leaseToken 租约令牌
 * @param fencingToken 单调执行隔离令牌
 * @param leaseUntil 租约截止时间
 * @param deadlineAt 任务总超时截止时间
 * @param parameters 参数
 * @param steps 步骤
 */
public record ClaimedTask(
        UUID executionId,
        UUID taskInstanceId,
        UUID parentTaskInstanceId,
        String taskName,
        UUID definitionId,
        int definitionVersion,
        String definitionDigest,
        UUID leaseToken,
        long fencingToken,
        Instant leaseUntil,
        Instant deadlineAt,
        Map<String, Object> parameters,
        List<ClaimedStep> steps
) {
    /**
     * 创建兼容旧测试与协议数据的任务租约。
     */
    public ClaimedTask(UUID executionId, UUID taskInstanceId, UUID parentTaskInstanceId, String taskName,
                       UUID leaseToken, Instant leaseUntil, Instant deadlineAt, Map<String, Object> parameters,
                       List<ClaimedStep> steps) {
        this(executionId, taskInstanceId, parentTaskInstanceId, taskName, null, 0, null,
                leaseToken, 0L, leaseUntil,
                deadlineAt, parameters, steps);
    }

    /**
     * 创建兼容已包含 fencing token 的旧协议任务租约。
     */
    public ClaimedTask(UUID executionId, UUID taskInstanceId, UUID parentTaskInstanceId, String taskName,
                       UUID leaseToken, long fencingToken, Instant leaseUntil, Instant deadlineAt,
                       Map<String, Object> parameters, List<ClaimedStep> steps) {
        this(executionId, taskInstanceId, parentTaskInstanceId, taskName, null, 0, null,
                leaseToken, fencingToken, leaseUntil, deadlineAt, parameters, steps);
    }

    /**
     * 校验 Scheduler 下发的任务定义与步骤执行契约摘要。
     */
    public void verifyDefinitionDigest() {
        if (definitionDigest == null || definitionDigest.isBlank()) {
            return;
        }
        if (definitionId == null || definitionVersion < 1 || !definitionDigest.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Claimed task definition digest metadata is invalid");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, definitionId.toString());
            updateDigest(digest, Integer.toString(definitionVersion));
            updateDigest(digest, taskName);
            for (ClaimedStep step : steps) {
                updateDigest(digest, step.stepDefinitionId().toString());
                updateDigest(digest, step.name());
                updateDigest(digest, step.stepKind());
                updateDigest(digest, step.scriptPackage());
                updateDigest(digest, step.scriptVersion());
                updateDigest(digest, step.scriptReleaseDigest());
                updateDigest(digest, step.entrypoint());
                step.argumentsTemplate().forEach(value -> updateDigest(digest, value));
                updateDigest(digest, Long.toString(step.timeoutSeconds()));
                updateDigest(digest, step.failurePolicy());
                updateDigest(digest, Integer.toString(step.sequenceNumber()));
                updateDigest(digest, Integer.toString(step.maxAttempts()));
            }
            if (!definitionDigest.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IllegalArgumentException("Claimed task definition digest does not match payload");
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
