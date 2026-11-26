package com.yuyutian.mytools.news.Model;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 订阅、不可变每日运行参数与任务执行凭证。 */
public final class SchedulingModels {
    private SchedulingModels() { }

    /** 首期每个所有者一份四分类内容订阅，版本零仅用于首次创建。 */
    public record SubscriptionChange(@Min(0) long expectedRevision, boolean enabled,
            @NotBlank @Size(max = 64) String zoneId,
            @NotBlank @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String cutoffTime) { }

    /** 不含投递目标的内容订阅视图。 */
    public record Subscription(UUID id, long revision, boolean enabled, String zoneId, String cutoffTime,
                               LocalDate nextDate, Instant nextDueAt, Instant updatedAt) { }

    /** 运行状态与调度状态分别展示，日报生成不等于消息投递。 */
    public record DailyRun(UUID id, UUID subscriptionId, long ownerId, long subscriptionRevision,
            LocalDate businessDate, String zoneId, Instant cutoff, Instant expiresAt, int skippedDays,
            String nodeId, String status, UUID taskId, String taskStatus, UUID editionId, long lastFence,
            String errorCode, Instant createdAt, Instant updatedAt) { }

    /** 出站请求的短租约，过期持有者不能更新任务对账结果。 */
    public record OutboxClaim(UUID runId, UUID token, int attempts) { }

    /** 仅在请求期间使用的执行租约，禁止写入日志、任务结果或数据库。 */
    public record ExecutionProof(UUID executionId, UUID leaseToken) {
        /** 屏蔽租约凭据。 */
        @Override public String toString() { return "ExecutionProof[redacted]"; }
    }

    /** Scheduler 在线校验后返回的最小授权范围。 */
    public record ExecutionGrant(UUID taskId, String businessId, String idempotencyKey, String nodeId,
                                 long fencingToken, Instant leaseUntil) { }

    /** 执行器只接收生成结果标识，不携带完整日报或身份资料。 */
    public record GenerationReceipt(UUID runId, UUID editionId, String status) { }
}
