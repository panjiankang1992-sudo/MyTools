package com.yuyutian.mytools.reader.model.adaptation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 公共投影不包含定位符、调用凭据、内部计划或邻章正文。 */
public final class AdaptationViews {
    private AdaptationViews() {
    }

    /** 创建收据的稳定响应，重放不随任务后续状态改变。 */
    public record Accepted(UUID adaptationId, UUID chapterId, long revisionNumber, AdaptationRequestKind requestKind,
                           AdaptationStatus status, String currentStage, int pollAfterMs, Instant createdAt) {
    }

    /** 派生请求的服务端身份，不接受客户端重新声明目标书籍。 */
    public record Target(UUID shelfBookId, UUID chapterId) {
    }

    /** 对外历史分页只发布有范围及期限约束的游标。 */
    public record History(List<HistoryItem> items, String nextCursor) {
        /** 固定当前页，保持请求结果不可变。 */
        public History {
            items = List.copyOf(items);
        }
    }

    /** 轮询只传输轻量状态，不携带用户意图或正文。 */
    public record Progress(UUID adaptationId, AdaptationStatus status, String currentStage, long version,
                           int candidateCount, String lastErrorCode, int pollAfterMs, Instant createdAt,
                           Instant startedAt, Instant finishedAt) {
    }

    /** 历史条目保留业务意图，但不重复传输任何候选正文。 */
    public record HistoryItem(UUID adaptationId, UUID shelfBookId, UUID chapterId, String chapterTitle,
                              long revisionNumber, AdaptationRequestKind requestKind, AdaptationLineage lineage,
                              String intent, AdaptationStatus status, String currentStage, UUID selectedAttemptId,
                              int attemptCount, String modelId, String lastErrorCode, Instant createdAt, Instant finishedAt,
                              AdaptationStyleModels.Summary styleTemplate) {
        /** 旧版本没有风格快照，保持原调用约定。 */
        public HistoryItem(UUID adaptationId, UUID shelfBookId, UUID chapterId, String chapterTitle,
                           long revisionNumber, AdaptationRequestKind requestKind, AdaptationLineage lineage,
                           String intent, AdaptationStatus status, String currentStage, UUID selectedAttemptId,
                           int attemptCount, String modelId, String lastErrorCode, Instant createdAt, Instant finishedAt) {
            this(adaptationId, shelfBookId, chapterId, chapterTitle, revisionNumber, requestKind, lineage, intent,
                    status, currentStage, selectedAttemptId, attemptCount, modelId, lastErrorCode, createdAt, finishedAt, null);
        }
        /** 日志不能通过记录类型的默认输出包含用户意图。 */
        @Override
        public String toString() {
            return "HistoryItem[adaptationId=" + adaptationId + ", content=redacted]";
        }
    }

    /** 仓储内部键集分页，服务层负责将页标封装为绑定用户范围的游标。 */
    public record HistoryPage(List<HistoryItem> items, Long nextBeforeRevision) {
        /** 固定当前页集合，避免调用方修改历史投影。 */
        public HistoryPage {
            items = List.copyOf(items);
        }
    }

    /** 公共候选摘要不显示 PLAN、CRITIC 或安全隔离的正文。 */
    public record Candidate(UUID attemptId, int attemptNo, String callKind, String callStatus,
                            String disposition, boolean selected, boolean hasViewableOutput) {
    }

    /** 单次按需读取的正文，来源与是否采用由数据库判定。 */
    public record Output(UUID attemptId, String content, String contentSha256, String viewStatus) {
        /** 正文不进入默认诊断日志。 */
        @Override
        public String toString() {
            return "Output[attemptId=" + attemptId + ", content=redacted]";
        }
    }

    /** 单版本投影只读取一个采用正文，未知来源不能被标为当前。 */
    public record Detail(HistoryItem version, String sourceRelation, Instant sourceCheckedAt,
                         Output result, List<Candidate> attempts) {
        /** 固定候选摘要集合。 */
        public Detail {
            attempts = List.copyOf(attempts);
        }
    }
}
