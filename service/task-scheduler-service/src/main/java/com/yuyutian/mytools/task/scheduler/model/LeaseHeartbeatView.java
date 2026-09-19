package com.yuyutian.mytools.task.scheduler.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * 执行租约状态。
 *
 * @param leaseUntil 租约截止时间
 * @param cancelRequested 是否请求取消
 * @param leaseState 租约状态
 */
public record LeaseHeartbeatView(Instant leaseUntil, boolean cancelRequested, String leaseState,
                                  @JsonIgnore WorkloadAssertion workloadAuthorization) {
    /** 普通任务保留原有三字段协议。 */
    public LeaseHeartbeatView(Instant leaseUntil, boolean cancelRequested, String leaseState) {
        this(leaseUntil, cancelRequested, leaseState, null);
    }

    /** 心跳诊断不能输出最新授权 token。 */
    @Override
    public String toString() {
        return "LeaseHeartbeatView[leaseState=" + leaseState + ", authorization=redacted]";
    }
}
