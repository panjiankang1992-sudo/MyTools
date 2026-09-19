package com.yuyutian.mytools.task.scheduler.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** 显式 HTTP 传输投影；持久化必须使用忽略授权字段的业务视图而非本类型。 */
public final class WorkloadTransport {
    private WorkloadTransport() {
    }

    /** 平铺原领取契约，并且仅在 HTTP 响应中附加短期凭据。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Claim(@JsonUnwrapped ClaimedTaskView task, String workloadAssertion, Instant workloadAssertionExpiresAt) {
        /** 从内存授权构建一次响应。 */
        public Claim(ClaimedTaskView task) {
            this(task, task.workloadAuthorization() == null ? null : task.workloadAuthorization().token(),
                    task.workloadAuthorization() == null ? null : task.workloadAuthorization().expiresAt());
        }

        /** 传输投影诊断仍不暴露凭据。 */
        @Override
        public String toString() {
            return "WorkloadTransport.Claim[redacted]";
        }
    }

    /** 心跳传输返回下一 generation 的 token，业务心跳对象本身不序列化它。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Heartbeat(@JsonUnwrapped LeaseHeartbeatView lease, String workloadAssertion, Instant workloadAssertionExpiresAt) {
        /** 从轮换后的内存授权构建一次响应。 */
        public Heartbeat(LeaseHeartbeatView lease) {
            this(lease, lease.workloadAuthorization() == null ? null : lease.workloadAuthorization().token(),
                    lease.workloadAuthorization() == null ? null : lease.workloadAuthorization().expiresAt());
        }

        /** 传输投影诊断仍不暴露凭据。 */
        @Override
        public String toString() {
            return "WorkloadTransport.Heartbeat[redacted]";
        }
    }
}
