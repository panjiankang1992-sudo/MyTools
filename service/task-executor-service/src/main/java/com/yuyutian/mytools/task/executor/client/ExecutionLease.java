package com.yuyutian.mytools.task.executor.client;

import java.time.Instant;

/**
 * 执行租约续期结果。
 *
 * @param leaseUntil 租约截止时间
 * @param cancelRequested 是否请求取消
 * @param leaseState 租约状态
 */
public record ExecutionLease(Instant leaseUntil, boolean cancelRequested, String leaseState) {
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
