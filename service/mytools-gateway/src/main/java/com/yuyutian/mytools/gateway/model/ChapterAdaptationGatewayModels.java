package com.yuyutian.mytools.gateway.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** App 章节改编白名单投影，不包含正文执行授权、模型密钥、定位符或内部计划。 */
public final class ChapterAdaptationGatewayModels {
    private ChapterAdaptationGatewayModels() { }

    /** 必填业务意图和乐观版本，不允许 owner、正文、模型或任意路径。 */
    public record Create(String idempotencyKey, String intent, long expectedBindingRevision,
                         long expectedCatalogRevision, String expectedSourceSha256, String templateCode, Long templateVersion) {
        /** 兼容旧客户端，没有模板时不隐式更改执行协议。 */
        public Create(String idempotencyKey, String intent, long expectedBindingRevision,
                      long expectedCatalogRevision, String expectedSourceSha256) {
            this(idempotencyKey, intent, expectedBindingRevision, expectedCatalogRevision, expectedSourceSha256, null, null);
        }
        /** 用户意图不得通过默认记录输出进入诊断。 */
        @Override public String toString() { return "Create[redacted]"; }
    }
    /** 准备请求只携带幂等键。 */
    public record Ensure(String idempotencyKey) { }
    /** 风格摘要不包含管理提示词。 */
    public record StyleSummary(String code, int version, String name, String description, String promptSha256) { }
    /** 只读风格下拉数据。 */
    public record StyleCatalog(List<StyleSummary> items) { }
    /** 单书准备能力。 */
    public record Capability(UUID shelfBookId, String status, long bindingRevision, long catalogRevision,
                             String catalogSha256, String reasonCode, Integer pollAfterMs) { }
    /** 服务端权威章节身份。 */
    public record Chapter(UUID chapterId, int index, String title, String contentKind, String sourceSha256,
                          boolean eligible, String ineligibleReason) { }
    /** 不携带 URL 的权威目录分页。 */
    public record Catalog(UUID shelfBookId, long bindingRevision, long catalogRevision, String catalogSha256,
                          List<Chapter> items, String nextCursor) { }
    /** 异步请求收据，不能据此宣称已经生成成功。 */
    public record Accepted(UUID adaptationId, UUID chapterId, long revisionNumber, String requestKind,
                           String status, String currentStage, int pollAfterMs, Instant createdAt) { }
    /** 无正文、无意图的轻量进度。 */
    public record Progress(UUID adaptationId, String status, String currentStage, long version, int candidateCount,
                           String lastErrorCode, int pollAfterMs, Instant createdAt, Instant startedAt, Instant finishedAt) { }
    /** 异步原文核验，不含正文；CURRENT 仅在短有效期内说明刚刚检查过的窗口。 */
    public record SourceCheck(UUID adaptationId, String status, Instant sourceCheckedAt, Instant validUntil,
                              int pollAfterMs, String reasonCode, long bindingRevision, long catalogRevision, String sourceSha256) { }
    /** 固定告知内容，按纯文本展示，不作为可调用地址或 HTML。 */
    public record DisclosurePayload(String schemaVersion, String providerCode, String contractSha256, String providerName,
                                    String providerOrigin, String dataUseNotice, String retentionNotice, String rightsNotice) { }
    /** 告知内容与权利声明版本。 */
    public record Disclosure(String version, String disclosureSha256, String rightsAttestationVersion, DisclosurePayload payload) { }
    /** 账户能力与当前明确同意状态。 */
    public record Features(boolean readEnabled, boolean createEnabled, String consentStatus, long consentRevision,
                           boolean hasActiveConsent, Instant acceptedAt, String reasonCode, Disclosure disclosure) { }
    /** 带修订屏障的明确同意，禁止迟到同意覆盖撤销。 */
    public record ConsentAccept(String disclosureVersion, String disclosureSha256, boolean accepted, boolean rightsAttested,
                                long expectedConsentRevision) { }
    /** 优化与重新改编保留不同的父子和触发关系。 */
    public record Lineage(UUID childAdaptationId, UUID rootAdaptationId, UUID parentAdaptationId, UUID triggerAdaptationId) { }
    /** 单版本历史元数据。 */
    public record HistoryItem(UUID adaptationId, UUID shelfBookId, UUID chapterId, String chapterTitle,
                              long revisionNumber, String requestKind, Lineage lineage, String intent, String status,
                              String currentStage, UUID selectedAttemptId, int attemptCount, String modelId,
                              String lastErrorCode, Instant createdAt, Instant finishedAt, StyleSummary styleTemplate) {
        /** 旧历史没有模板快照。 */
        public HistoryItem(UUID adaptationId, UUID shelfBookId, UUID chapterId, String chapterTitle,
                           long revisionNumber, String requestKind, Lineage lineage, String intent, String status,
                           String currentStage, UUID selectedAttemptId, int attemptCount, String modelId,
                           String lastErrorCode, Instant createdAt, Instant finishedAt) {
            this(adaptationId, shelfBookId, chapterId, chapterTitle, revisionNumber, requestKind, lineage, intent, status,
                    currentStage, selectedAttemptId, attemptCount, modelId, lastErrorCode, createdAt, finishedAt, null);
        }
        /** 历史意图不进入诊断。 */
        @Override public String toString() { return "HistoryItem[redacted]"; }
    }
    /** 带范围游标的历史页。 */
    public record History(List<HistoryItem> items, String nextCursor) { }
    /** 可展示候选摘要。 */
    public record Candidate(UUID attemptId, int attemptNo, String callKind, String callStatus, String disposition,
                            boolean selected, boolean hasViewableOutput) { }
    /** 仅由 Reader 判定可展示的正文。 */
    public record Output(UUID attemptId, String content, String contentSha256, String viewStatus) {
        /** 正文不进入诊断。 */
        @Override public String toString() { return "Output[redacted]"; }
    }
    /** 只比较 Reader 的冻结原章和正式采用结果，不读取当前书源。 */
    public record Comparison(UUID adaptationId, UUID attemptId, String originalSha256, String resultSha256,
                             String mode, List<ComparisonHunk> hunks, String original, String adapted, String fallbackReason) {
        /** 比较正文不能进入默认日志。 */
        @Override public String toString() { return "Comparison[redacted]"; }
    }
    /** 有界段落差异的固定字段集合。 */
    public record ComparisonHunk(String kind, int originalStart, int adaptedStart, List<String> original, List<String> adapted) {
        /** 片段正文不能进入默认日志。 */
        @Override public String toString() { return "ComparisonHunk[redacted]"; }
    }
    /** 不可变版本详情与单份采用正文。 */
    public record Detail(HistoryItem version, String sourceRelation, Instant sourceCheckedAt,
                         Output result, List<Candidate> attempts) {
        /** 嵌套内容不进入诊断。 */
        @Override public String toString() { return "Detail[redacted]"; }
    }
}
