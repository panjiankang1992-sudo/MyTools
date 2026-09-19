package com.yuyutian.mytools.reader.repository.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionFence;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionViews;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 内部执行身份的唯一接管路径，先验证在线授权，再在短事务中绑定 owner 和单调 fence。 */
@Repository
public class AdaptationExecutionRepository {
    private final JdbcTemplate jdbc;
    private final AdaptationContextRepository contexts;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AdaptationStyleRepository styles;

    /** 注入持久事务及封存校验器，不在事务中执行 Scheduler 或书源网络调用。 */
    @Autowired
    public AdaptationExecutionRepository(JdbcTemplate jdbc, AdaptationContextRepository contexts, PlatformTransactionManager manager) {
        this(jdbc, contexts, manager, Clock.systemUTC());
    }

    /** 为过期、接管及竞争测试注入可控时间。 */
    public AdaptationExecutionRepository(JdbcTemplate jdbc, AdaptationContextRepository contexts, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
        this.styles = new AdaptationStyleRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(), manager);
    }

    /** 只有本方法允许更高 fence 接管；同 fence 同执行幂等，不回退状态、快照或调用预算。 */
    public AdaptationExecutionViews.Claim claim(WorkloadAuthorization authorization) {
        requireIndependent();
        return transaction.execute(ignored -> {
            Map<String, Object> row = lock(authorization, false);
            Object oldFence = row.get("fencing_token");
            Object oldExecution = row.get("current_execution_id");
            if ((oldFence == null) != (oldExecution == null)
                    || (oldFence != null && (authorization.fencingToken() < number(row, "fencing_token")
                    || (authorization.fencingToken() == number(row, "fencing_token")
                    && !authorization.executionId().toString().equals(oldExecution))))) throw fenced();
            AdaptationStatus status = AdaptationStatus.valueOf(text(row, "status"));
            if (status == AdaptationStatus.PENDING_DISPATCH) throw failure(ErrorCode.ADAPTATION_TASK_BIND_PENDING);
            AdaptationContextSnapshot snapshot = "SEALED".equals(row.get("context_status")) ? contexts.sealedForClaim(row) : null;
            String nextStatus = status.name();
            String stage = text(row, "current_stage");
            if (status == AdaptationStatus.QUEUED || status == AdaptationStatus.CONTEXT_FREEZING) {
                nextStatus = snapshot == null ? "CONTEXT_FREEZING" : "ANALYZING";
                stage = snapshot == null ? "CONTEXT_FREEZING" : "PLAN_PENDING";
            } else if (snapshot == null) {
                throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
            }
            // 重复领取没有语义变化时不增加业务 version，不产生假进度。
            if (oldFence == null || authorization.fencingToken() != number(row, "fencing_token")
                    || !nextStatus.equals(row.get("status")) || !stage.equals(row.get("current_stage"))) {
                int changed = jdbc.update("""
                        UPDATE novel_chapter_adaptation SET current_execution_id = ?, fencing_token = ?, status = ?, current_stage = ?,
                            started_at = COALESCE(started_at, ?), updated_at = ?, version = version + 1
                        WHERE owner_id = ? AND id = ? AND task_instance_id = ? AND version = ? AND deletion_state = 'LIVE'
                            AND cancel_requested_at IS NULL AND dispatch_abort_error_code IS NULL AND deadline_at > ?
                        """, authorization.executionId().toString(), authorization.fencingToken(), nextStatus, stage,
                        stamp(clock.instant()), stamp(clock.instant()), row.get("owner_id"), row.get("id"),
                        authorization.taskInstanceId().toString(), row.get("version"), stamp(clock.instant()));
                if (changed != 1) throw fenced();
                row = one("SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ?", row.get("owner_id"), row.get("id"));
            }
            AdaptationExecutionViews.State view = state(row);
            String action = !view.pendingAttempts().isEmpty() ? "RECOVER_ATTEMPTS" : snapshot == null ? "PREPARE_CONTEXT"
                    : view.status() == AdaptationStatus.ANALYZING ? "ANALYZE" : "RESUME";
            String intent = text(row, "normalized_intent_text");
            if (!AdaptationText.sha256(intent).equals(row.get("intent_sha256"))) throw fenced();
            var style = styles.parseSnapshot(row.get("style_template_snapshot_json"));
            if (style != null) {
                // 只能执行建立任务时冻结的提示词，不能重新查最新模板。
                intent = AdaptationStyleRepository.generationIntent(style, intent);
                if (!intent.equals(row.get("generation_intent_text"))
                        || !AdaptationText.sha256(intent).equals(row.get("generation_intent_sha256"))
                        || !"novel-adaptation-v2".equals(row.get("prompt_version"))) throw fenced();
            } else if (row.get("generation_intent_text") != null || "novel-adaptation-v2".equals(row.get("prompt_version"))) throw fenced();
            var inputs = new AdaptationExecutionViews.Inputs(intent, text(row, "prompt_version"), text(row, "constraint_version"),
                    binary(row, "provider_deployment_id"), text(row, "provider_code"), binary(row, "model_id"),
                    number(row, "credential_generation"), "DIRECT".equals(row.get("consent_policy"))
                    ? "not-required" : binary(row, "disclosure_version"));
            requireFresh(authorization);
            if (!instant(row, "deadline_at").isAfter(clock.instant())) throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
            return new AdaptationExecutionViews.Claim(view, action, inputs, AdaptationExecutionViews.context(snapshot));
        });
    }

    /** 其他正文操作只解析严格相等的当前执行，不能隐式接管。 */
    public AdaptationExecutionFence current(WorkloadAuthorization authorization) {
        requireIndependent();
        return transaction.execute(ignored -> {
            Map<String, Object> row = lock(authorization, false);
            requireCurrent(authorization, row);
            return new AdaptationExecutionFence(authorization.resourceId(), authorization.taskInstanceId(), authorization.executionId(),
                    authorization.fencingToken(), number(row, "chapter_delete_epoch"), authorization.authorizedUntil());
        });
    }

    /** 状态读取也要求同执行，但允许观察 Reader 已停止、Scheduler 尚未收敛的窗口。 */
    public AdaptationExecutionViews.State status(WorkloadAuthorization authorization) {
        requireIndependent();
        return transaction.execute(ignored -> {
            Map<String, Object> row = lock(authorization, true);
            requireCurrent(authorization, row);
            return state(row);
        });
    }

    private Map<String, Object> lock(WorkloadAuthorization authorization, boolean allowStopped) {
        requireFresh(authorization);
        // 只有经签名资源摘要认证的 ID 可反查 owner；之后所有查询均带 owner 范围。
        Map<String, Object> known = one("SELECT owner_id, shelf_book_id, content_binding_id, chapter_id FROM novel_chapter_adaptation WHERE id = ?",
                authorization.resourceId().toString());
        if (known == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        Map<String, Object> shelf = one("SELECT id FROM shelf_book WHERE owner_id = ? AND id = ? AND deleted = FALSE FOR UPDATE",
                known.get("owner_id"), known.get("shelf_book_id"));
        if (shelf == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        one("SELECT id FROM shelf_book_content_binding WHERE owner_id = ? AND id = ? FOR UPDATE", known.get("owner_id"), known.get("content_binding_id"));
        Map<String, Object> chapter = one("SELECT adaptation_delete_epoch FROM shelf_book_chapter WHERE owner_id = ? AND id = ? FOR UPDATE",
                known.get("owner_id"), known.get("chapter_id"));
        Map<String, Object> row = one("SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ? FOR UPDATE",
                known.get("owner_id"), authorization.resourceId().toString());
        if (row == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        if (!"LIVE".equals(row.get("deletion_state")) || chapter == null
                || number(chapter, "adaptation_delete_epoch") != number(row, "chapter_delete_epoch")) throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        if (row.get("task_instance_id") == null) throw failure(ErrorCode.ADAPTATION_TASK_BIND_PENDING);
        if (!authorization.taskInstanceId().toString().equals(row.get("task_instance_id"))) throw fenced();
        if (!allowStopped) {
            if (row.get("cancel_requested_at") != null || row.get("dispatch_abort_error_code") != null
                    || AdaptationStatus.valueOf(text(row, "status")).terminal()
                    || "CANCEL_REQUESTED".equals(row.get("status"))) throw fenced();
            if (!instant(row, "deadline_at").isAfter(clock.instant())) throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
        }
        requireFresh(authorization);
        return row;
    }

    /** 同包调用账本在同一事务内复用锁顺序，禁止先释放执行锁再写预算。 */
    Map<String, Object> lockedCurrent(WorkloadAuthorization authorization, boolean allowStopped) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                    .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) throw fenced();
        Map<String, Object> row = lock(authorization, allowStopped);
        requireCurrent(authorization, row);
        return row;
    }

    private void requireCurrent(WorkloadAuthorization authorization, Map<String, Object> row) {
        if (!authorization.executionId().toString().equals(row.get("current_execution_id")) || row.get("fencing_token") == null
                || authorization.fencingToken() != number(row, "fencing_token")) throw fenced();
        requireFresh(authorization);
    }

    /** 同包恢复读取复用已锁父行状态，不在不同事务之间拼接预算与正文。 */
    AdaptationExecutionViews.State state(Map<String, Object> row) {
        List<AdaptationExecutionViews.PendingAttempt> pending = jdbc.query("""
                SELECT provider_attempt_id, call_kind, status, execution_id, fencing_token, request_sha256
                FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ?
                    AND status IN ('REGISTERED', 'SEND_STARTED') ORDER BY attempt_no LIMIT 6
                """, (rs, index) -> new AdaptationExecutionViews.PendingAttempt(UUID.fromString(rs.getString(1)), rs.getString(2),
                rs.getString(3), UUID.fromString(rs.getString(4)), rs.getLong(5), rs.getString(6)), row.get("owner_id"), row.get("id"));
        if (pending.size() > 5) throw fenced();
        AdaptationStatus status = AdaptationStatus.valueOf(text(row, "status"));
        boolean stop = status.terminal() || status == AdaptationStatus.CANCEL_REQUESTED || row.get("cancel_requested_at") != null
                || row.get("dispatch_abort_error_code") != null || !instant(row, "deadline_at").isAfter(clock.instant());
        return new AdaptationExecutionViews.State(UUID.fromString(text(row, "id")), UUID.fromString(text(row, "current_execution_id")),
                number(row, "fencing_token"), status, text(row, "current_stage"), instant(row, "deadline_at"), stop,
                (int) number(row, "provider_call_budget_remaining"), (int) number(row, "provider_retry_budget_remaining"),
                number(row, "provider_millis_remaining"), List.copyOf(pending));
    }

    private void requireFresh(WorkloadAuthorization authorization) {
        if (authorization == null || authorization.resource() != WorkloadResource.CHAPTER_ADAPTATION
                || authorization.resourceId() == null || authorization.taskInstanceId() == null || authorization.executionId() == null
                || authorization.fencingToken() <= 0 || authorization.authorizedUntil() == null
                || !authorization.authorizedUntil().isAfter(clock.instant())) throw fenced();
    }
    private static void requireIndependent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw fenced();
    }
    private Map<String, Object> one(String sql, Object... args) { var rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    private static String text(Map<String, Object> row, String key) { return (String) row.get(key); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String binary(Map<String, Object> row, String key) { return new String((byte[]) row.get(key), StandardCharsets.UTF_8); }
    private static Instant instant(Map<String, Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
    private static ChapterAdaptationException fenced() { return failure(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private static ChapterAdaptationException failure(ErrorCode code) { return new ChapterAdaptationException(code); }
}
