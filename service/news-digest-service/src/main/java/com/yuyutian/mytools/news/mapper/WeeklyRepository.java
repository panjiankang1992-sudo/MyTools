package com.yuyutian.mytools.news.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.WeeklyModels.*;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionGrant;
import com.yuyutian.mytools.news.common.ErrorCode;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.service.NewsSchedulerClient.TaskState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 手动任务的受理、短租约派发、执行代次及不可变报告均持久保存。 */
@Repository
public class WeeklyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    /** 复用新闻服务数据库和事务管理器。 */
    public WeeklyRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(manager);
    }

    /** 原子受理，重复键、活动任务和五分钟去抖都登记成稳定的请求映射。 */
    public View create(long owner, String key, String node, Instant now) {
        return tx.execute(transaction -> {
            lock();
            Optional<Job> replay = replay(owner, key, "CREATE");
            if (replay.isPresent()) return replay.orElseThrow().view(true);
            // 自用任务数量有界，避免无上限请求账本占满数据库。
            capacity();
            // 长任务完成后仍保留五分钟复用窗口，避免用户查看结果后立即重复采集。
            long recent = now.minusSeconds(300).toEpochMilli();
            List<Job> existing = jdbc.query("SELECT * FROM news_weekly_job WHERE owner_id=? AND "
                    + "(status IN ('QUEUED','RUNNING') OR created_ms>=? "
                    + "OR (status IN ('SUCCEEDED','PARTIAL') AND updated_ms>=?)) "
                    + "ORDER BY created_ms DESC,id DESC LIMIT 1",
                    this::row, owner, recent, recent);
            Job job;
            if (existing.isEmpty()) {
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO news_weekly_job(id,owner_id,node_id,window_end_ms,created_ms,updated_ms,expires_ms,"
                                + "status,attempt,revision,dispatch_id,progress_json,next_dispatch_ms) VALUES (?,?,?,?,?,?,?,'QUEUED',1,1,?,?,?)",
                        id.toString(), owner, node, now.toEpochMilli(), now.toEpochMilli(), now.toEpochMilli(),
                        now.plus(Duration.ofHours(2)).toEpochMilli(), UUID.randomUUID().toString(), initialProgress(), now.toEpochMilli());
                job = internal(id);
            } else job = existing.getFirst();
            remember(owner, key, "CREATE", job.id(), now);
            return job.view(!existing.isEmpty());
        });
    }

    /** 重试仍使用原时间窗和报告身份，新派发标识使旧任务自动失去发布权限。 */
    public View retry(long owner, UUID id, Retry request, Instant now) {
        return tx.execute(transaction -> {
            lock();
            String operation = "RETRY:" + id + ":" + request.expectedRevision();
            Optional<Job> replay = replay(owner, request.idempotencyKey(), operation);
            if (replay.isPresent()) return replay.orElseThrow().view(true);
            Job job = owned(owner, id);
            if (job.revision() != request.expectedRevision() || !List.of("FAILED", "PARTIAL").contains(job.status())
                    || active(owner).isPresent() || job.attempt() >= 10) throw new NewsException(ErrorCode.NEWS_032);
            capacity();
            jdbc.update("UPDATE news_weekly_job SET status='QUEUED',attempt=attempt+1,revision=revision+1,dispatch_id=?,"
                            + "task_id=NULL,last_fence=0,error_code=NULL,progress_json=?,updated_ms=?,expires_ms=?,"
                            + "next_dispatch_ms=?,dispatch_token=NULL,dispatch_until_ms=0 WHERE id=?",
                    UUID.randomUUID().toString(), initialProgress(), now.toEpochMilli(), now.plus(Duration.ofHours(2)).toEpochMilli(),
                    now.toEpochMilli(), id.toString());
            remember(owner, request.idempotencyKey(), operation, id, now);
            return internal(id).view(false);
        });
    }

    /** 查询本人活动任务，空结果不触发生成。 */
    public Optional<Job> active(long owner) {
        return jdbc.query("SELECT * FROM news_weekly_job WHERE owner_id=? AND status IN ('QUEUED','RUNNING') "
                + "ORDER BY created_ms DESC,id DESC LIMIT 1", this::row, owner).stream().findFirst();
    }

    /** 所有者隔离的单条查询，不允许通过报告编号访问他人任务。 */
    public Job owned(long owner, UUID id) {
        Job result=jdbc.query("SELECT * FROM news_weekly_job WHERE id=? AND owner_id=?", this::row, id.toString(), owner)
                .stream().findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
        if ("EXPIRED".equals(result.status())) throw new NewsException(ErrorCode.NEWS_026);
        return result;
    }

    /** 历史保留完成或失败任务，失败且无报告也能在应用重启后重试。 */
    public History history(long owner, UUID before, int limit) {
        Job cursor = before == null ? null : owned(owner, before);
        long time = cursor == null ? Long.MAX_VALUE : cursor.createdAt().toEpochMilli();
        String id = cursor == null ? "~" : cursor.id().toString();
        List<Job> rows = jdbc.query("SELECT * FROM news_weekly_job WHERE owner_id=? AND status<>'EXPIRED' AND (report_revision>0 OR status NOT IN ('QUEUED','RUNNING')) "
                        + "AND (created_ms<? OR (created_ms=? AND id<?)) ORDER BY created_ms DESC,id DESC LIMIT ?",
                this::row, owner, time, time, id, limit+1);
        List<View> items = rows.stream().limit(limit).map(job -> job.view(false)).toList();
        return new History(items, rows.size()>limit ? items.getLast().id().toString() : null);
    }

    /** 九十天后删除正文快照，保留幂等墓碑，旧点击键不能重新生成报告。 */
    public List<UUID> expire(Instant now) {
        return tx.execute(transaction -> {
            lock();
            jdbc.update("UPDATE news_weekly_job SET status='EXPIRED',error_code=NULL WHERE created_ms<? AND status NOT IN ('QUEUED','RUNNING','EXPIRED')",
                    now.minus(java.time.Duration.ofDays(90)).toEpochMilli());
            jdbc.update("DELETE FROM news_weekly_report WHERE job_id IN (SELECT id FROM news_weekly_job WHERE status='EXPIRED')");
            // 返回全部有界墓碑，使上次文件删除失败后可以继续清理。
            return jdbc.query("SELECT id FROM news_weekly_job WHERE status='EXPIRED'",(rs,index) -> UUID.fromString(rs.getString(1)));
        });
    }

    /** 读取当前不可变报告修订，未完成任务不会假装存在报告。 */
    public JsonNode report(long owner, UUID id) {
        Job job = owned(owner, id);
        return jdbc.query("SELECT payload_json FROM news_weekly_report WHERE job_id=? AND revision=? AND owner_id=?",
                (rs, index) -> decode(rs.getString(1)), id.toString(), job.reportRevision(), owner)
                .stream().findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    /** 通过短事务领取一项已存在的手动任务，不扫描或创建任何日报。 */
    public Optional<Claim> claim(Instant now) {
        return tx.execute(transaction -> {
            lock();
            List<Job> available = jdbc.query("SELECT * FROM news_weekly_job WHERE status IN ('QUEUED','RUNNING') "
                    + "AND next_dispatch_ms<=? AND dispatch_until_ms<=? ORDER BY created_ms,id LIMIT 1",
                    this::row, now.toEpochMilli(), now.toEpochMilli());
            if (available.isEmpty()) return Optional.empty();
            Job job = available.getFirst();
            UUID token = UUID.randomUUID();
            jdbc.update("UPDATE news_weekly_job SET dispatch_token=?,dispatch_until_ms=? WHERE id=?",
                    token.toString(), now.plusSeconds(90).toEpochMilli(), job.id().toString());
            return Optional.of(new Claim(job, token));
        });
    }

    /** 对账只更新当前派发代次；响应丢失后仍用原派发标识恢复。 */
    public void reconcile(Claim claim, TaskState task, String error, boolean permanent, Instant now) {
        tx.executeWithoutResult(transaction -> {
            Job job = locked(claim.job().id());
            List<String> token = jdbc.query("SELECT dispatch_token FROM news_weekly_job WHERE id=? AND dispatch_until_ms>?",
                    (rs,index) -> rs.getString(1), job.id().toString(), now.toEpochMilli());
            if (!job.dispatchId().equals(claim.job().dispatchId()) || token.isEmpty()
                    || !claim.token().toString().equals(token.getFirst())) return;
            String status = job.status();
            // 报告发布先于 Scheduler 完成时保留业务终态，不能反向写回 RUNNING。
            if (job.active()) {
                if (!job.expiresAt().isAfter(now) || permanent || (task != null && task.terminal())) {
                    status = job.reportRevision() > 0 ? "PARTIAL" : "FAILED";
                } else if (task != null && "RUNNING".equals(task.status())) status = "RUNNING";
            }
            String finalError = !job.active() ? job.errorCode() : !job.expiresAt().isAfter(now) ? ErrorCode.NEWS_036.name() : error != null ? error : !List.of("QUEUED","RUNNING").contains(status)
                    ? ErrorCode.NEWS_034.name() : job.errorCode();
            jdbc.update("UPDATE news_weekly_job SET task_id=?,status=?,error_code=?,next_dispatch_ms=?,dispatch_token=NULL,"
                            + "dispatch_until_ms=0,updated_ms=? WHERE id=?",
                    task != null ? task.id().toString() : job.taskId() == null ? null : job.taskId().toString(), status,
                    finalError, now.plusSeconds(error == null ? 15 : 60).toEpochMilli(), now.toEpochMilli(), job.id().toString());
        });
    }

    /** 按本次派发标识定位任务，旧重试代次无法再获得上下文。 */
    public Job dispatch(UUID id) {
        return jdbc.query("SELECT * FROM news_weekly_job WHERE dispatch_id=?", this::row, id.toString()).stream()
                .findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003));
    }

    /** 在事务内重新检查在线租约后持久更新进度或发布，阻止旧持有者覆写。 */
    public Job updateExecution(UUID dispatchId, Supplier<ExecutionGrant> authorize, JsonNode progress,
                               JsonNode report, Supplier<Instant> clock) {
        return tx.execute(transaction -> {
            Job job = locked(dispatch(dispatchId).id());
            ExecutionGrant grant = authorize.get();
            Instant now = clock.get();
            if (!dispatchId.equals(job.dispatchId()) || !dispatchId.toString().equals(grant.businessId())
                    || !("news:weekly:" + dispatchId).equals(grant.idempotencyKey()) || !job.nodeId().equals(grant.nodeId())
                    || (job.taskId()!=null && !job.taskId().equals(grant.taskId())) || grant.fencingToken()<job.lastFence()
                    || !grant.leaseUntil().isAfter(now) || !job.expiresAt().isAfter(now)) {
                throw new NewsException(ErrorCode.NEWS_016);
            }
            // 同一代次报告已原子落库后，重复提交返回既有结果，不增加第二份修订。
            if (!job.active()) return job;
            long reportRevision = job.reportRevision();
            String status = "RUNNING";
            if (report != null) {
                reportRevision++;
                jdbc.update("INSERT INTO news_weekly_report(job_id,revision,owner_id,created_ms,payload_json) VALUES (?,?,?,?,?)",
                        job.id().toString(), reportRevision, job.ownerId(), now.toEpochMilli(), encode(report));
                status = report.path("status").asText();
            }
            jdbc.update("UPDATE news_weekly_job SET task_id=?,last_fence=?,status=?,report_revision=?,progress_json=?,"
                            + "revision=revision+?,updated_ms=?,error_code=NULL WHERE id=?",
                    grant.taskId().toString(), grant.fencingToken(), status, reportRevision,
                    encode(progress == null ? job.progress() : progress), report == null ? 0 : 1, now.toEpochMilli(), job.id().toString());
            return internal(job.id());
        });
    }

    private void lock() { jdbc.queryForObject("SELECT id FROM news_weekly_lock WHERE id=1 FOR UPDATE", Integer.class); }
    private Job locked(UUID id) { return jdbc.query("SELECT * FROM news_weekly_job WHERE id=? FOR UPDATE", this::row, id.toString())
            .stream().findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003)); }
    private Job internal(UUID id) { return jdbc.query("SELECT * FROM news_weekly_job WHERE id=?", this::row,id.toString())
            .stream().findFirst().orElseThrow(() -> new NewsException(ErrorCode.NEWS_003)); }
    private void capacity() {
        if (Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_job", Long.class)) >= 10000
                || Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_request", Long.class)) >= 100000)
            throw new NewsException(ErrorCode.NEWS_035);
    }
    private Optional<Job> replay(long owner, String key, String operation) {
        return jdbc.query("SELECT job_id,operation FROM news_weekly_request WHERE owner_id=? AND request_key=?", (rs,index) -> {
            if (!operation.equals(rs.getString(2))) throw new NewsException(ErrorCode.NEWS_004);
            return owned(owner, UUID.fromString(rs.getString(1)));
        }, owner,key).stream().findFirst();
    }
    private void remember(long owner, String key, String operation, UUID job, Instant now) {
        jdbc.update("INSERT INTO news_weekly_request(owner_id,request_key,job_id,operation,created_ms) VALUES (?,?,?,?,?)",
                owner,key,job.toString(),operation,now.toEpochMilli());
    }
    private String initialProgress() { return "{\"stage\":\"QUEUED\",\"completedCategories\":0}"; }
    private String encode(JsonNode node) {
        try { return json.writeValueAsString(node); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }
    private JsonNode decode(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new NewsException(ErrorCode.NEWS_009); }
    }
    private Job row(ResultSet rs, int index) throws SQLException {
        String task = rs.getString("task_id");
        return new Job(UUID.fromString(rs.getString("id")), rs.getLong("owner_id"), rs.getString("node_id"),
                Instant.ofEpochMilli(rs.getLong("window_end_ms")), Instant.ofEpochMilli(rs.getLong("created_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_ms")), Instant.ofEpochMilli(rs.getLong("expires_ms")),
                rs.getString("status"),rs.getInt("attempt"),rs.getLong("revision"),UUID.fromString(rs.getString("dispatch_id")),
                task == null ? null : UUID.fromString(task),rs.getLong("last_fence"),rs.getLong("report_revision"),
                decode(rs.getString("progress_json")),rs.getString("error_code"));
    }
}
