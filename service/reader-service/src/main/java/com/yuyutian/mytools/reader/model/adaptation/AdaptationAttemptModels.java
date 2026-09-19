package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.UUID;

/** 模型调用账本的受限内部命令；不包含 owner、地址、密钥或可覆盖的发布配置。 */
public final class AdaptationAttemptModels {
    private AdaptationAttemptModels() { }

    /** 固定阶段预算的总和为 660 秒；额外传输不会凭空获得新的时间预算。 */
    public enum Kind {
        PLAN(90000), GENERATE(180000), CRITIC(105000), REPAIR(180000);
        private final long millis;
        Kind(long millis) { this.millis = millis; }
        /** 返回本阶段允许预占的最大 Provider 等待毫秒数。 */
        public long millis() { return millis; }
    }

    /** 随机调用身份和原始 Provider 请求字节摘要共同形成幂等键。 */
    public record Reserve(UUID providerAttemptId, Kind callKind, String requestSha256) { }

    /** 成功预留不等于允许发送，必须再取得一次性发送许可。 */
    public record Reservation(UUID attemptId, UUID providerAttemptId, int attemptNo, Kind callKind,
                              String status, long reservedMillis, Instant expiresAt) { }

    /** 只有首次提交返回 maySend，重放仅允许恢复结算令牌，不能重发 Provider。 */
    public record SendPermit(UUID providerAttemptId, boolean maySend, int transmission, Instant sendBy,
                             Instant callDeadlineAt, Instant settlementExpiresAt, String attemptSettlementToken) {
        /** 诊断不包含可用于结算的令牌。 */
        @Override
        public String toString() { return "SendPermit[providerAttemptId=" + providerAttemptId + ", maySend=" + maySend + ", token=REDACTED]"; }
    }

    /** Provider 的归一终态；结构化阶段与正文阶段不能混用两种输出。 */
    public record Terminal(String status, String outputText, String structuredJson, String finishReason,
                           String providerRequestId, Long inputTokens, Long outputTokens, Integer httpStatus,
                           String errorCode, String diagnosticSha256) {
        /** 正文、响应元数据和结构化内容不写入诊断。 */
        @Override
        public String toString() { return "Terminal[status=" + status + ", payload=REDACTED]"; }
    }

    /** 保存结果与正式采用分离；取消或换代后的结果只能留档。 */
    public record Settlement(UUID attemptId, UUID providerAttemptId, String status, boolean archivedOnly,
                              String terminalPayloadSha256) { }
}
