package com.yuyutian.mytools.news.Model;

import java.time.Instant;
import java.util.UUID;

/** 周期采集及地区探测的持久审计模型。 */
public final class PipelineModels {
    private PipelineModels() { }
    /** 轮询租约不向管理查询返回令牌。 */
    public record PollClaim(String sourceId, UUID token, int failures) { }
    /** 来源轮询进度；未采集或冷却不能伪装为成功。 */
    public record PollState(String sourceId, Instant nextDueAt, int failures, String lastStatus, Instant lastFinishedAt, Instant leaseUntil) { }
    /** 一个节点一个六小时时间桶最多一个逻辑探测任务。 */
    public record RegionJob(UUID id, String nodeId, Instant bucketAt, Instant expiresAt, UUID taskId, String taskStatus,
                            String status, long lastFence, UUID observationId, String observationStatus,
                            String errorCode, Instant createdAt, Instant updatedAt) { }
    /** 任务结果不包括 IP、指纹、地理原始数据或凭据。 */
    public record RegionJobReceipt(UUID jobId, UUID observationId, String status, String observationStatus) { }
}
