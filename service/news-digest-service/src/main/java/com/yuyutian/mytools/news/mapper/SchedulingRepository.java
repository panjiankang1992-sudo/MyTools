package com.yuyutian.mytools.news.mapper;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.utils.SubscriptionSchedule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/** 订阅扫描、运行状态与出站队列使用同一数据库事务，不依赖进程内定时状态。 */
@Repository
public class SchedulingRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DispatchRepository dispatch;
    private final ControlCapacityRepository capacity;

    /** 创建持久调度仓储。 */
    public SchedulingRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager, DispatchRepository dispatch, ControlCapacityRepository capacity) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.dispatch = dispatch; this.capacity = capacity;
    }

    /** 查询本人订阅，首次配置前为空。 */
    public Optional<Subscription> subscription(long owner) {
        return jdbc.query("SELECT * FROM news_subscription WHERE owner_id = ?", this::subscriptionRow, owner).stream().findFirst();
    }

    /** 乐观版本避免并行编辑覆盖，停用或修改同时取消尚未产出内容的运行。 */
    public Subscription change(long owner, SubscriptionChange request, Instant now) {
        try {
            return tx.execute(status -> {
                capacity.lock();
                var old = jdbc.query("SELECT * FROM news_subscription WHERE owner_id = ? FOR UPDATE", this::subscriptionRow, owner).stream().findFirst();
                if (request.expectedRevision() != old.map(Subscription::revision).orElse(0L)) throw new NewsException(ErrorCode.NEWS_014);
                UUID id = old.map(Subscription::id).orElseGet(UUID::randomUUID);
                LocalDate date = request.enabled() ? SubscriptionSchedule.firstDate(now, request.cutoffTime(), request.zoneId()) : null;
                Long due = date == null ? null : SubscriptionSchedule.cutoff(date, request.cutoffTime(), request.zoneId()).toEpochMilli();
                if (old.isEmpty()) {
                    capacity.reserve(1,0,0,0);
                    jdbc.update("INSERT INTO news_subscription(id, owner_id, revision, enabled, zone_id, cutoff_time, next_date, next_due_ms, updated_ms) VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)",
                            id.toString(), owner, request.enabled(), request.zoneId(), request.cutoffTime(), date == null ? null : date.toString(), due, now.toEpochMilli());
                } else {
                    jdbc.update("UPDATE news_subscription SET revision = revision + 1, enabled = ?, zone_id = ?, cutoff_time = ?, next_date = ?, next_due_ms = ?, updated_ms = ? WHERE id = ?",
                            request.enabled(), request.zoneId(), request.cutoffTime(), date == null ? null : date.toString(), due, now.toEpochMilli(), id.toString());
                    jdbc.update("UPDATE news_daily_run SET status = 'CANCELLED', updated_ms = ? WHERE subscription_id = ? AND status IN ('QUEUED','DISPATCHED')", now.toEpochMilli(), id.toString());
                    dispatch.stopSubscription(id, !request.enabled(), now);
                }
                return subscription(owner).orElseThrow();
            });
        } catch (DuplicateKeyException exception) { throw new NewsException(ErrorCode.NEWS_014); }
    }

    /** 有界扫描，只补最近一期；超过十二小时的期次记录过期而不派发。 */
    public int scan(Instant now, String nodeId) {
        var ids = jdbc.query("SELECT id FROM news_subscription WHERE enabled = TRUE AND next_due_ms <= ? ORDER BY next_due_ms, id LIMIT 100",
                (row, index) -> row.getString(1), now.toEpochMilli());
        int created = 0;
        for (String id : ids) {
            // 行锁序列化不同进程的同一订阅扫描，日期唯一约束作为最终防线。
            boolean inserted = Boolean.TRUE.equals(tx.execute(status -> {
                capacity.lock();
                Subscription sub = jdbc.query("SELECT * FROM news_subscription WHERE id = ? FOR UPDATE", this::subscriptionRow, id).getFirst();
                if (!sub.enabled() || sub.nextDueAt().isAfter(now)) return false;
                LocalDate date = SubscriptionSchedule.latestDate(now, sub.cutoffTime(), sub.zoneId());
                Instant cutoff = SubscriptionSchedule.cutoff(date, sub.cutoffTime(), sub.zoneId());
                Instant expiry = cutoff.plus(12, ChronoUnit.HOURS);
                boolean expired = !now.isBefore(expiry);
                long owner = Objects.requireNonNull(jdbc.queryForObject("SELECT owner_id FROM news_subscription WHERE id = ?", Long.class, id));
                boolean exists = !jdbc.queryForList("SELECT id FROM news_daily_run WHERE subscription_id = ? AND business_date = ?", id, date.toString()).isEmpty();
                if (!exists) {
                    capacity.reserve(0,0,1,0);
                    UUID runId = UUID.randomUUID();
                    int skipped = Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(sub.nextDate(), date)));
                    jdbc.update("INSERT INTO news_daily_run(id, subscription_id, owner_id, subscription_revision, business_date, zone_id, cutoff_ms, expires_ms, skipped_days, node_id, status, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            runId.toString(), id, owner, sub.revision(), date.toString(), sub.zoneId(), cutoff.toEpochMilli(), expiry.toEpochMilli(), skipped, nodeId, expired ? "EXPIRED" : "QUEUED", now.toEpochMilli(), now.toEpochMilli());
                    if (!expired) jdbc.update("INSERT INTO news_task_outbox(run_id, state, available_ms) VALUES (?, 'PENDING', ?)", runId.toString(), now.toEpochMilli());
                    if (!expired) dispatch.capture(runId, UUID.fromString(id), owner, expiry, now);
                }
                LocalDate next = date.plusDays(1);
                jdbc.update("UPDATE news_subscription SET next_date = ?, next_due_ms = ? WHERE id = ?", next.toString(), SubscriptionSchedule.cutoff(next, sub.cutoffTime(), sub.zoneId()).toEpochMilli(), id);
                return !exists;
            }));
            if (inserted) created++;
        }
        return created;
    }

    /** 所有者隔离的最近运行列表。 */
    public List<DailyRun> runs(long owner, int limit) {
        return jdbc.query("SELECT * FROM news_daily_run WHERE owner_id = ? ORDER BY created_ms DESC, id DESC LIMIT ?", this::runRow, owner, limit);
    }

    /** 所有者隔离的运行详情。 */
    public DailyRun run(long owner, UUID id) {
        DailyRun run = run(id);
        if (run.ownerId() != owner) throw new NewsException(ErrorCode.NEWS_003);
        return run;
    }

    /** 内部读取运行，不能由外部请求绕过所有者校验。 */
    public DailyRun run(UUID id) {
        return jdbc.query("SELECT * FROM news_daily_run WHERE id = ?", this::runRow, id.toString()).stream().findFirst()
                .orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    /** 取消立即阻止发布，Scheduler 的远程取消由原出站记录继续对账。 */
    public DailyRun cancel(long owner, UUID id, Instant now) {
        run(owner, id);
        jdbc.update("UPDATE news_daily_run SET status = 'CANCELLED', updated_ms = ? WHERE id = ? AND owner_id = ? AND status IN ('QUEUED','DISPATCHED')", now.toEpochMilli(), id.toString(), owner);
        dispatch.stopRun(id, now);
        return run(owner, id);
    }

    /** 获取一个两分钟短租约，抢占失败者不产生外部请求。 */
    public Optional<OutboxClaim> claim(Instant now) { return claim(now, "EDITION"); }

    /** 按任务类型领取独立队列记录，避免生成器误消费地区任务。 */
    public Optional<OutboxClaim> claim(Instant now, String kind) {
        var candidates = jdbc.query("SELECT run_id, attempts FROM news_task_outbox WHERE job_kind = ? AND state = 'PENDING' AND available_ms <= ? AND (lease_until_ms IS NULL OR lease_until_ms <= ?) ORDER BY available_ms, run_id LIMIT 100",
                (row, index) -> new OutboxClaim(UUID.fromString(row.getString(1)), UUID.randomUUID(), row.getInt(2)), kind, now.toEpochMilli(), now.toEpochMilli());
        for (OutboxClaim item : candidates) {
            int changed = jdbc.update("UPDATE news_task_outbox SET lease_token = ?, lease_until_ms = ?, attempts = attempts + 1 WHERE run_id = ? AND state = 'PENDING' AND available_ms <= ? AND (lease_until_ms IS NULL OR lease_until_ms <= ?)",
                    item.token().toString(), now.plusSeconds(120).toEpochMilli(), item.runId().toString(), now.toEpochMilli(), now.toEpochMilli());
            if (changed == 1) return Optional.of(item);
        }
        return Optional.empty();
    }

    /** 统一提交对账结果，丢失租约时连同任务状态更新一起回滚。 */
    public void reconcile(OutboxClaim claim, UUID taskId, String taskStatus, String errorCode, boolean permanent,
                          boolean taskTerminal, Instant next, Instant now) {
        tx.executeWithoutResult(status -> {
            DailyRun run = lockedRun(claim.runId());
            if (run.taskId() != null && taskId != null && !run.taskId().equals(taskId)) throw new NewsException(ErrorCode.NEWS_004);
            String state = run.status();
            if (Set.of("QUEUED", "DISPATCHED").contains(state)) {
                // 过期、调度失败和已派发分别展示，不能把远端成功直接视作日报已生成。
                if (!now.isBefore(run.expiresAt())) state = "EXPIRED";
                else if (permanent || taskTerminal) state = "FAILED";
                else if (taskId != null) state = "DISPATCHED";
            }
            // 创建响应晚于工作回调时，以事务内最新发布状态为准。
            String visibleError = state.equals("READY") && ErrorCode.NEWS_019.name().equals(errorCode) ? null : errorCode;
            jdbc.update("UPDATE news_daily_run SET task_id = COALESCE(task_id, ?), task_status = COALESCE(?, task_status), status = ?, error_code = ?, updated_ms = ? WHERE id = ?",
                    taskId == null ? null : taskId.toString(), taskStatus, state, visibleError, now.toEpochMilli(), run.id().toString());
            String outboxState = permanent ? "DEAD" : taskTerminal ? "DONE" : "PENDING";
            int updated = jdbc.update("UPDATE news_task_outbox SET state = ?, available_ms = ?, lease_token = NULL, lease_until_ms = NULL, error_code = ? WHERE run_id = ? AND lease_token = ? AND lease_until_ms > ?",
                    outboxState, next.toEpochMilli(), visibleError, run.id().toString(), claim.token().toString(), now.toEpochMilli());
            if (updated != 1) throw new NewsException(ErrorCode.NEWS_016);
        });
    }

    /** 管理员修复配置后恢复原队列记录，绝不创建新运行或新幂等键。 */
    public void redrive(UUID id, Instant now) {
        tx.executeWithoutResult(status -> {
            DailyRun run = lockedRun(id);
            int changed = jdbc.update("UPDATE news_task_outbox SET state = 'PENDING', available_ms = ?, error_code = NULL WHERE run_id = ? AND state = 'DEAD' AND (lease_until_ms IS NULL OR lease_until_ms <= ?)", now.toEpochMilli(), id.toString(), now.toEpochMilli());
            if (changed != 1) throw new NewsException(ErrorCode.NEWS_016);
            if (run.status().equals("FAILED")) {
                String next = now.isBefore(run.expiresAt()) ? "QUEUED" : "EXPIRED";
                jdbc.update("UPDATE news_daily_run SET status = ?, error_code = NULL, updated_ms = ? WHERE id = ?", next, now.toEpochMilli(), id.toString());
            }
        });
    }

    /** 脱敏运维视图不返回队列租约令牌。 */
    public Map<String, Object> outbox(UUID id) {
        run(id);
        var rows = jdbc.queryForList("SELECT state, attempts, available_ms, lease_until_ms, error_code FROM news_task_outbox WHERE run_id = ?", id.toString());
        return rows.isEmpty() ? Map.of("state", "NOT_REQUIRED") : rows.getFirst();
    }

    /** 生成前绑定任务与递增 fence，未知任务或旧执行不能覆盖新执行。 */
    public DailyRun accept(UUID id, ExecutionGrant grant, Instant now) {
        return tx.execute(status -> {
            DailyRun run = lockForPublication(id);
            requireExecutable(run, grant, now);
            jdbc.update("UPDATE news_daily_run SET task_id = COALESCE(task_id, ?), last_fence = ?, updated_ms = ? WHERE id = ?",
                    grant.taskId().toString(), grant.fencingToken(), now.toEpochMilli(), id.toString());
            return run(id);
        });
    }

    /** 发布事务与停用串行，提交前重新在线验证租约，日报和 READY 状态原子落库。 */
    public GenerationReceipt publish(UUID id, ExecutionGrant first, Supplier<ExecutionGrant> verify,
                                      Supplier<UUID> saveEdition, Supplier<Instant> now) {
        return tx.execute(status -> {
            DailyRun run = lockForPublication(id);
            ExecutionGrant current = verify.get();
            if (!current.taskId().equals(first.taskId()) || current.fencingToken() != first.fencingToken()) throw new NewsException(ErrorCode.NEWS_016);
            requireExecutable(run, current, now.get());
            if (run.editionId() != null) return new GenerationReceipt(id, run.editionId(), "READY");
            UUID editionId = saveEdition.get();
            // 最后再检查时间预算，超时的保存与运行状态在同一事务中回滚。
            requireExecutable(run, current, now.get());
            jdbc.update("UPDATE news_daily_run SET edition_id = ?, status = 'READY', error_code = NULL, updated_ms = ? WHERE id = ?", editionId.toString(), now.get().toEpochMilli(), id.toString());
            dispatch.publish(id, editionId, now.get());
            requireExecutable(run, current, now.get());
            return new GenerationReceipt(id, editionId, "READY");
        });
    }

    private DailyRun lockForPublication(UUID id) {
        DailyRun initial = run(id);
        var subscriptions = jdbc.query("SELECT * FROM news_subscription WHERE id = ? FOR UPDATE", this::subscriptionRow, initial.subscriptionId().toString());
        DailyRun run = lockedRun(id);
        if (!subscriptions.getFirst().enabled() && run.editionId() == null) throw new NewsException(ErrorCode.NEWS_016);
        return run;
    }

    private void requireExecutable(DailyRun run, ExecutionGrant grant, Instant now) {
        if (!Set.of("QUEUED", "DISPATCHED", "READY").contains(run.status()) || !now.isBefore(run.expiresAt())
                || !grant.leaseUntil().isAfter(now.plusSeconds(5)) || grant.fencingToken() < Math.max(1, run.lastFence())
                || !grant.businessId().equals(run.id().toString()) || !grant.idempotencyKey().equals("news:" + run.id())
                || !grant.nodeId().equals(run.nodeId()) || (run.taskId() != null && !run.taskId().equals(grant.taskId()))) {
            throw new NewsException(ErrorCode.NEWS_016);
        }
    }

    private DailyRun lockedRun(UUID id) {
        return jdbc.query("SELECT * FROM news_daily_run WHERE id = ? FOR UPDATE", this::runRow, id.toString()).stream().findFirst()
                .orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    private Subscription subscriptionRow(ResultSet row, int index) throws SQLException {
        String date = row.getString("next_date");
        return new Subscription(UUID.fromString(row.getString("id")), row.getLong("revision"), row.getBoolean("enabled"), row.getString("zone_id"), row.getString("cutoff_time"),
                date == null ? null : LocalDate.parse(date), row.getObject("next_due_ms") == null ? null : Instant.ofEpochMilli(row.getLong("next_due_ms")), Instant.ofEpochMilli(row.getLong("updated_ms")));
    }

    private DailyRun runRow(ResultSet row, int index) throws SQLException {
        return new DailyRun(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("subscription_id")), row.getLong("owner_id"), row.getLong("subscription_revision"),
                LocalDate.parse(row.getString("business_date")), row.getString("zone_id"), Instant.ofEpochMilli(row.getLong("cutoff_ms")), Instant.ofEpochMilli(row.getLong("expires_ms")),
                row.getInt("skipped_days"), row.getString("node_id"), row.getString("status"), uuid(row.getString("task_id")), row.getString("task_status"), uuid(row.getString("edition_id")),
                row.getLong("last_fence"), row.getString("error_code"), Instant.ofEpochMilli(row.getLong("created_ms")), Instant.ofEpochMilli(row.getLong("updated_ms")));
    }

    private UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
