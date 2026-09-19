package com.yuyutian.mytools.task.executor.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 执行租约续期结果。
 *
 * @param leaseUntil 租约截止时间
 * @param cancelRequested 是否请求取消
 * @param leaseState 租约状态
 */
public record ExecutionLease(Instant leaseUntil, boolean cancelRequested, String leaseState,
                             @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String workloadAssertion,
                             @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Instant workloadAssertionExpiresAt) {
    /** 创建不包含传输态授权的兼容心跳结果。 */
    public ExecutionLease(Instant leaseUntil, boolean cancelRequested, String leaseState) {
        this(leaseUntil, cancelRequested, leaseState, null, null);
    }

    /** 诊断对象不输出授权正文。 */
    @Override
    public String toString() {
        return "ExecutionLease[leaseUntil=" + leaseUntil + ", cancelRequested=" + cancelRequested
                + ", leaseState=" + leaseState + ", credentials=REDACTED]";
    }

    /**
     * 创建兼容旧调用方的有效租约。
     *
     * @param leaseUntil 租约截止时间
     * @param cancelRequested 是否请求取消
     */
    public ExecutionLease(Instant leaseUntil, boolean cancelRequested) {
        this(leaseUntil, cancelRequested, "ACTIVE");
    }
}
