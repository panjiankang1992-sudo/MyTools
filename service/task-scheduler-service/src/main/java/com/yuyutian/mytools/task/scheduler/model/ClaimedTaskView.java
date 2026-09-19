package com.yuyutian.mytools.task.scheduler.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行节点领取的任务。
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
 * @param mayCreateChildren 任务执行期间是否可能创建并等待子任务
 * @param parameters 任务参数
 * @param steps 脚本步骤
 */
public record ClaimedTaskView(
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
        boolean mayCreateChildren,
        Map<String, Object> parameters,
        List<ClaimedStepView> steps,
        @JsonIgnore WorkloadAssertion workloadAuthorization
) {
    /** 保持无工作负载授权的既有业务与测试构造契约。 */
    public ClaimedTaskView(UUID executionId, UUID taskInstanceId, UUID parentTaskInstanceId, String taskName,
                           UUID definitionId, int definitionVersion, String definitionDigest, UUID leaseToken,
                           long fencingToken, Instant leaseUntil, Instant deadlineAt, boolean mayCreateChildren,
                           Map<String, Object> parameters, List<ClaimedStepView> steps) {
        this(executionId, taskInstanceId, parentTaskInstanceId, taskName, definitionId, definitionVersion, definitionDigest,
                leaseToken, fencingToken, leaseUntil, deadlineAt, mayCreateChildren, parameters, steps, null);
    }

    /** 只在传输内存附加授权，参数和原始执行契约保持不变。 */
    public ClaimedTaskView withWorkloadAuthorization(WorkloadAssertion assertion) {
        return new ClaimedTaskView(executionId, taskInstanceId, parentTaskInstanceId, taskName, definitionId, definitionVersion,
                definitionDigest, leaseToken, fencingToken, leaseUntil, deadlineAt, mayCreateChildren, parameters, steps, assertion);
    }

    /** 默认诊断不输出租约凭据、签名 token 和任务参数。 */
    @Override
    public String toString() {
        return "ClaimedTaskView[executionId=" + executionId + ", sensitive=redacted]";
    }
}
