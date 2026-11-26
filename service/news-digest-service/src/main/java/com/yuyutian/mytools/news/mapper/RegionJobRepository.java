package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.Model.PipelineModels.*;
import com.yuyutian.mytools.news.Model.RegionModels.RegionObservation;
import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 地区任务与报告的持久身份，不让重复任务刷新观测时间。 */
@Repository
public class RegionJobRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RegistryCapacityRepository registry;
    /** 创建地区任务仓储。 */
    public RegionJobRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, RegistryCapacityRepository registry) { this.jdbc = jdbc; tx = new TransactionTemplate(manager); this.registry=registry; }

    /** 每个节点当前六小时桶最多安排一次，崩溃恢复不补旧桶。 */
    public Optional<UUID> scan(String node, Instant now) {
        return tx.execute(status -> {
            registry.lock();
            var cursor=jdbc.queryForList("SELECT node_id FROM news_region_schedule WHERE node_id=? FOR UPDATE",String.class,node);
            if(cursor.isEmpty()) {
                // 节点与任务原子预留，任务满额时不能留下新的空游标。
                registry.reserve(0,0,1,0,0);
                jdbc.update("INSERT INTO news_region_schedule(node_id,capacity_recorded) VALUES(?,TRUE)",node);
            }
            long bucket = Math.floorDiv(now.toEpochMilli(), 21600000L) * 21600000L;
            var previous = jdbc.queryForList("SELECT id FROM news_region_job WHERE node_id = ? AND (bucket_ms = ? OR status IN ('QUEUED','DISPATCHED')) FOR UPDATE", node, bucket);
            if (!previous.isEmpty()) return Optional.empty();
            registry.reserve(0,0,0,1,0);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO news_region_job(id, node_id, bucket_ms, expires_ms, status, created_ms, updated_ms, capacity_recorded) VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, TRUE)",
                    id.toString(), node, bucket, now.plusSeconds(900).toEpochMilli(), now.toEpochMilli(), now.toEpochMilli());
            jdbc.update("INSERT INTO news_task_outbox(run_id, job_kind, state, available_ms) VALUES (?, 'REGION', 'PENDING', ?)", id.toString(), now.toEpochMilli());
            return Optional.of(id);
        });
    }

    /** 获取服务端冻结的地区任务。 */
    public RegionJob job(UUID id) {
        return jdbc.query("SELECT * FROM news_region_job WHERE id = ?", this::row, id.toString()).stream().findFirst()
                .orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }
    /** 返回最多一百条近期记录，不返回报告指纹或租约凭据。 */
    public List<RegionJob> jobs() { return jdbc.query("SELECT * FROM news_region_job ORDER BY created_ms DESC, id DESC LIMIT 100", this::row); }

    /** 接收前在线检查已完成，报告保存和任务状态在同一事务中提交。 */
    public RegionJobReceipt complete(UUID id, ExecutionGrant grant, RegionObservation report, Supplier<ExecutionGrant> verify,
                                     Runnable save, Supplier<Instant> now) {
        return tx.execute(status -> {
            RegionJob job = locked(id);
            require(job, grant, now.get());
            ExecutionGrant latest = verify.get();
            require(job, latest, now.get());
            if (!grant.taskId().equals(latest.taskId()) || grant.fencingToken() != latest.fencingToken()) throw new NewsException(ErrorCode.NEWS_016);
            if (job.observationId() != null) {
                // 原报告由上层要求完全相同后才能重放，不能由另一个执行替换。
                if (!job.observationId().equals(report.id())) throw new NewsException(ErrorCode.NEWS_004);
                save.run();
                return receipt(job);
            }
            save.run();
            require(job, latest, now.get());
            jdbc.update("UPDATE news_region_job SET task_id = COALESCE(task_id, ?), status = 'STORED', last_fence = ?, observation_id = ?, observation_status = ?, error_code = NULL, updated_ms = ? WHERE id = ?",
                    grant.taskId().toString(), grant.fencingToken(), report.id().toString(), report.status().name(), now.get().toEpochMilli(), id.toString());
            return receipt(job(id));
        });
    }

    /** 工作步骤重试可查询原回执，避免在新执行中再次探测并替换已经提交的报告。 */
    public Optional<RegionJobReceipt> receipt(UUID id, ExecutionGrant grant, Instant now) {
        RegionJob job = job(id); require(job, grant, now);
        return job.observationId() == null ? Optional.empty() : Optional.of(receipt(job));
    }

    /** 当前任务可立即取消，已接收地区报告保持不可变。 */
    public RegionJob cancel(UUID id, Instant now) {
        job(id);
        jdbc.update("UPDATE news_region_job SET status = 'CANCELLED', updated_ms = ? WHERE id = ? AND status IN ('QUEUED','DISPATCHED')", now.toEpochMilli(), id.toString());
        return job(id);
    }

    /** 对账更新和队列租约提交原子化，过期持有者不能写入。 */
    public void reconcile(OutboxClaim claim, UUID taskId, String taskStatus, String error, boolean permanent, boolean terminal, Instant next, Instant now) {
        tx.executeWithoutResult(status -> {
            RegionJob job = locked(claim.runId());
            if (job.taskId() != null && taskId != null && !job.taskId().equals(taskId)) throw new NewsException(ErrorCode.NEWS_004);
            String state = job.status();
            if (Set.of("QUEUED", "DISPATCHED").contains(state)) {
                if (!now.isBefore(job.expiresAt())) state = "EXPIRED";
                else if (permanent || terminal) state = "FAILED";
                else if (taskId != null) state = "DISPATCHED";
            }
            String visibleError = state.equals("STORED") && ErrorCode.NEWS_020.name().equals(error) ? null : error;
            jdbc.update("UPDATE news_region_job SET task_id = COALESCE(task_id, ?), task_status = COALESCE(?, task_status), status = ?, error_code = ?, updated_ms = ? WHERE id = ?",
                    taskId == null ? null : taskId.toString(), taskStatus, state, visibleError, now.toEpochMilli(), job.id().toString());
            int count = jdbc.update("UPDATE news_task_outbox SET state = ?, available_ms = ?, lease_token = NULL, lease_until_ms = NULL, error_code = ? WHERE run_id = ? AND job_kind = 'REGION' AND lease_token = ? AND lease_until_ms > ?",
                    permanent ? "DEAD" : terminal ? "DONE" : "PENDING", next.toEpochMilli(), visibleError, job.id().toString(), claim.token().toString(), now.toEpochMilli());
            if (count != 1) throw new NewsException(ErrorCode.NEWS_016);
        });
    }

    /** 修复配置后仅恢复原来的死信身份，过期任务继续取消对账。 */
    public void redrive(UUID id, Instant now) {
        tx.executeWithoutResult(status -> {
            RegionJob job = locked(id);
            if (jdbc.update("UPDATE news_task_outbox SET state = 'PENDING', available_ms = ?, error_code = NULL WHERE run_id = ? AND job_kind = 'REGION' AND state = 'DEAD'", now.toEpochMilli(), id.toString()) != 1) throw new NewsException(ErrorCode.NEWS_016);
            if (job.status().equals("FAILED")) jdbc.update("UPDATE news_region_job SET status = ?, error_code = NULL, updated_ms = ? WHERE id = ?",
                    now.isBefore(job.expiresAt()) ? "QUEUED" : "EXPIRED", now.toEpochMilli(), id.toString());
        });
    }

    private void require(RegionJob job, ExecutionGrant grant, Instant now) {
        if (!Set.of("QUEUED", "DISPATCHED", "STORED").contains(job.status()) || !now.isBefore(job.expiresAt())
                || !grant.leaseUntil().isAfter(now.plusSeconds(5)) || grant.fencingToken() < Math.max(1, job.lastFence())
                || !grant.businessId().equals(job.id().toString()) || !grant.idempotencyKey().equals("news:region:" + job.id())
                || !grant.nodeId().equals(job.nodeId()) || (job.taskId() != null && !job.taskId().equals(grant.taskId()))) throw new NewsException(ErrorCode.NEWS_016);
    }
    private RegionJob locked(UUID id) { return jdbc.query("SELECT * FROM news_region_job WHERE id = ? FOR UPDATE", this::row, id.toString()).stream().findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003)); }
    private RegionJobReceipt receipt(RegionJob job) { return new RegionJobReceipt(job.id(), job.observationId(), "STORED", job.observationStatus()); }
    private RegionJob row(ResultSet row, int index) throws SQLException {
        return new RegionJob(UUID.fromString(row.getString("id")), row.getString("node_id"), Instant.ofEpochMilli(row.getLong("bucket_ms")), Instant.ofEpochMilli(row.getLong("expires_ms")),
                uuid(row.getString("task_id")), row.getString("task_status"), row.getString("status"), row.getLong("last_fence"), uuid(row.getString("observation_id")),
                row.getString("observation_status"), row.getString("error_code"), Instant.ofEpochMilli(row.getLong("created_ms")), Instant.ofEpochMilli(row.getLong("updated_ms")));
    }
    private UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
