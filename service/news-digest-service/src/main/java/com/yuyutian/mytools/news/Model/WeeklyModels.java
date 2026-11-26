package com.yuyutian.mytools.news.Model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 手动七天任务协议，公开视图不包含所有者、执行节点和租约信息。 */
public final class WeeklyModels {
    private WeeklyModels() { }

    /** 生成请求只接受客户端幂等键，时间窗由服务端冻结。 */
    public record Create(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String idempotencyKey) {
        /** 拒绝客户端注入身份、脚本和截止时间。 */
        @JsonAnySetter public void unknown(String key, Object value) { throw new NewsException(ErrorCode.NEWS_002); }
    }

    /** 显式失败重试固定原报告窗口，修订用于拒绝过期页面操作。 */
    public record Retry(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String idempotencyKey,
                        @Min(1) long expectedRevision) {
        /** 拒绝未知参数。 */
        @JsonAnySetter public void unknown(String key, Object value) { throw new NewsException(ErrorCode.NEWS_002); }
    }

    /** 内部持久任务快照，每次重试拥有独立派发身份。 */
    public record Job(UUID id, long ownerId, String nodeId, Instant windowEnd, Instant createdAt, Instant updatedAt,
                      Instant expiresAt, String status, int attempt, long revision, UUID dispatchId, UUID taskId,
                      long lastFence, long reportRevision, JsonNode progress, String errorCode) {
        /** 固定所有分类统一的七天起点。 */
        public Instant windowStart() { return windowEnd.minus(Duration.ofDays(7)); }
        /** 只有未终结任务需要派发和恢复。 */
        public boolean active() { return Set.of("QUEUED", "RUNNING").contains(status); }
        /** 构造只包含用户所需字段的状态视图。 */
        public View view(boolean reused) {
            return new View(id, status, attempt, revision, windowStart(), windowEnd, createdAt, updatedAt,
                    progress, reportRevision > 0 ? id : null, reportRevision, errorCode, reused);
        }
    }

    /** 用户查询和受理共用的稳定状态视图。 */
    public record View(UUID id, String status, int attempt, long revision, Instant windowStart, Instant windowEnd,
                       Instant createdAt, Instant updatedAt, JsonNode progress, UUID reportId, long reportRevision,
                       String errorCode, boolean reused) { }

    /** 历史以任务维度分页，重试修订不重复列出。 */
    public record History(List<View> items, String nextCursor) { }

    /** 派发器短租约只用于进程间协调，不暴露给 App。 */
    public record Claim(Job job, UUID token) { }

    /** 工作进程从受授权的服务端取得窗口，不能自行指定所有者。 */
    public record Context(UUID jobId, UUID dispatchId, int attempt, Instant windowStart, Instant windowEnd,
                          String status, long reportRevision) { }
}
