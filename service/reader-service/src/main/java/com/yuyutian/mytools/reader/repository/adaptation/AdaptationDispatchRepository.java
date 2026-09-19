package com.yuyutian.mytools.reader.repository.adaptation;

import com.yuyutian.mytools.reader.config.ReaderAdaptationDispatchProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.Action;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.Claim;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationSchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 可恢复的改编派发控制面；只读取身份与状态，不读取意图、上下文或候选正文。 */
@Repository
public class AdaptationDispatchRepository {
    private static final String HEADER = """
            SELECT id, owner_id, shelf_book_id, content_binding_id, chapter_id, chapter_delete_epoch,
                task_instance_id, status, deletion_state, cancel_requested_at, deadline_at,
                dispatch_epoch, dispatch_attempt_count, dispatch_claim_owner, dispatch_claimed_until,
                next_dispatch_at, dispatch_abort_error_code
            FROM novel_chapter_adaptation WHERE id = ?
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReaderAdaptationDispatchProperties properties;
    private final Clock clock;

    /** 创建短事务仓储，持锁期间不执行调度 RPC。 */
    @Autowired
    public AdaptationDispatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                        ReaderAdaptationDispatchProperties properties) {
        this(jdbc, manager, properties, Clock.systemUTC());
    }

    /** 为租约过期、崩溃接管和截止时间验证提供固定时钟。 */
    public AdaptationDispatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                        ReaderAdaptationDispatchProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.properties = properties;
        this.clock = clock;
    }

    /** 领取一个到期任务；提交次数只对 SUBMIT 消耗，查询与取消可持续恢复。 */
    public Claim claim(String worker) {
        requireIndependent();
        if (worker == null || !worker.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid adaptation dispatch worker");
        }
        return transaction.execute(ignored -> {
            // MySQL TIMESTAMP(6) 只保留微秒，返回的租约必须与数据库精度相同才能做严格等值 CAS。
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<String> candidates = jdbc.queryForList("""
                    SELECT id FROM novel_chapter_adaptation
                    WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') AND next_dispatch_at <= ?
                        AND (dispatch_claimed_until IS NULL OR dispatch_claimed_until <= ?)
                    ORDER BY next_dispatch_at, id LIMIT 8
                    """, String.class, timestamp(now), timestamp(now));
            for (String candidate : candidates) {
                // 各租约领取遵守相同的 shelf → binding → chapter → adaptation 顺序，跳过正在使用的范围。
                State state = lock(UUID.fromString(candidate), true);
                if (state == null || terminal(state.row()) || instant(state.row(), "next_dispatch_at").isAfter(now)
                        || (instant(state.row(), "dispatch_claimed_until") != null
                        && instant(state.row(), "dispatch_claimed_until").isAfter(now))) {
                    continue;
                }
                Map<String, Object> row = state.row();
                String abort = abortReason(state);
                int attempts = (int) number(row, "dispatch_attempt_count");
                UUID task = uuid(row, "task_instance_id");
                if (!stopping(state) && task == null && attempts >= properties.maximumAttempts()) {
                    abort = ErrorCode.ADAPTATION_DISPATCH_FAILED.code();
                }
                Action action = abort != null || stopping(state) ? Action.CANCEL : task == null ? Action.SUBMIT : Action.OBSERVE;
                if (action == Action.SUBMIT && !"PENDING_DISPATCH".equals(row.get("status"))) {
                    // 已进入执行阶段却丢失调度身份属于不一致，先建立取消屏障，不能创建替代任务。
                    abort = ErrorCode.ADAPTATION_DISPATCH_FAILED.code();
                    action = Action.CANCEL;
                }
                long epoch = Math.addExact(number(row, "dispatch_epoch"), 1);
                int nextAttempts = attempts + (action == Action.SUBMIT ? 1 : 0);
                Instant until = now.plusSeconds(properties.leaseSeconds());
                jdbc.update("""
                        UPDATE novel_chapter_adaptation SET dispatch_epoch = ?, dispatch_attempt_count = ?,
                            dispatch_claim_owner = ?, dispatch_claimed_until = ?, dispatch_abort_error_code = ?,
                            current_stage = CASE WHEN ? = 'CANCEL' THEN 'DISPATCH_CANCELLING' ELSE current_stage END,
                            updated_at = ?, version = version + 1 WHERE id = ?
                        """, epoch, nextAttempts, worker, timestamp(until), abort, action.name(), timestamp(now), candidate);
                return new Claim(UUID.fromString(candidate), number(row, "owner_id"), task, epoch, worker, until, nextAttempts, action);
            }
            return null;
        });
    }

    /** 回绑和观察响应；返回 true 时由事务外补偿取消，旧领取不能取消仍被新领取正常使用的任务。 */
    public boolean acknowledge(Claim claim, SchedulerView view) {
        requireIndependent();
        if (view == null || !claim.adaptationId().equals(view.adaptationId())
                || (claim.taskInstanceId() != null && !claim.taskInstanceId().equals(view.taskInstanceId()))) {
            throw new AdaptationSchedulerException(false);
        }
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            State state = lock(claim.adaptationId(), false);
            if (!owns(state, claim)) {
                // 租约丢失不等于业务取消；新 worker 可能刚成功回绑同一幂等任务。
                return requiresCompensation(state);
            }
            Map<String, Object> row = state.row();
            UUID knownTask = uuid(row, "task_instance_id");
            if (knownTask != null && !knownTask.equals(view.taskInstanceId())) {
                abort(row, ErrorCode.ADAPTATION_DISPATCH_FAILED.code());
                release(row, properties.observeSeconds());
                return false;
            }
            if (knownTask == null && view.taskInstanceId() != null) {
                jdbc.update("UPDATE novel_chapter_adaptation SET task_instance_id = ? WHERE id = ?",
                        view.taskInstanceId().toString(), row.get("id"));
                row.put("task_instance_id", view.taskInstanceId().toString());
            }
            String reason = abortReason(state);
            if (reason != null) {
                abort(row, reason);
            }
            if (!stopping(state) && (view.cancellationRecorded() || view.terminal() || view.taskInstanceId() == null)) {
                // Scheduler 成功不代表小说已通过验证；正文完成只由采用事务写入，不能靠状态轮询伪造。
                abort(row, ErrorCode.ADAPTATION_DISPATCH_FAILED.code());
            }
            if (stopping(state)) {
                if (view.cancellationSettled() && pendingAttempts(row) == 0) {
                    finish(row);
                } else {
                    jdbc.update("UPDATE novel_chapter_adaptation SET current_stage = 'DISPATCH_CANCELLING' WHERE id = ?", row.get("id"));
                    release(row, properties.observeSeconds());
                }
                return false;
            }
            if ("PENDING_DISPATCH".equals(row.get("status"))) {
                jdbc.update("UPDATE novel_chapter_adaptation SET status = 'QUEUED', current_stage = 'QUEUED' WHERE id = ?", row.get("id"));
            }
            release(row, properties.observeSeconds());
            return false;
        }));
    }

    /** 持久退避，不把超时当作未创建，也不在取消 RPC 失败时伪造终态。 */
    public void failed(Claim claim, boolean retryable) {
        requireIndependent();
        transaction.executeWithoutResult(ignored -> {
            State state = lock(claim.adaptationId(), false);
            if (!owns(state, claim)) {
                return;
            }
            Map<String, Object> row = state.row();
            String reason = abortReason(state);
            if (reason != null) {
                abort(row, reason);
            } else if ((claim.action() != Action.CANCEL && !retryable)
                    || (claim.action() == Action.SUBMIT && claim.submissionAttempt() >= properties.maximumAttempts())) {
                abort(row, ErrorCode.ADAPTATION_DISPATCH_FAILED.code());
            }
            // 稳定抖动避免重启时同一批任务同步冲击 Scheduler，取消失败仍保留恢复资格。
            int backoff = Math.min(30, (1 << Math.min(claim.submissionAttempt(), 4))
                    + Math.floorMod(claim.adaptationId().hashCode(), 3));
            release(row, stopping(state) ? properties.observeSeconds() : backoff);
        });
    }

    private State lock(UUID id, boolean skipLocked) {
        Map<String, Object> known = one(HEADER, id.toString());
        if (known == null) {
            return null;
        }
        String suffix = skipLocked ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
        Map<String, Object> shelf = one("SELECT deleted FROM shelf_book WHERE id = ? AND owner_id = ?" + suffix,
                known.get("shelf_book_id"), known.get("owner_id"));
        if (shelf == null || one("SELECT id FROM shelf_book_content_binding WHERE id = ? AND owner_id = ?" + suffix,
                known.get("content_binding_id"), known.get("owner_id")) == null) {
            return null;
        }
        Map<String, Object> chapter = one("SELECT adaptation_delete_epoch FROM shelf_book_chapter WHERE id = ? AND owner_id = ?" + suffix,
                known.get("chapter_id"), known.get("owner_id"));
        Map<String, Object> row = chapter == null ? null : one(HEADER + suffix, id.toString());
        return row == null ? null : new State(row, Boolean.TRUE.equals(shelf.get("deleted")), number(chapter, "adaptation_delete_epoch"));
    }

    private String abortReason(State state) {
        Map<String, Object> row = state.row();
        if (row.get("dispatch_abort_error_code") != null) {
            return row.get("dispatch_abort_error_code").toString();
        }
        if (!instant(row, "deadline_at").isAfter(clock.instant())) {
            return ErrorCode.ADAPTATION_DEADLINE_EXCEEDED.code();
        }
        if (state.shelfDeleted() || !"LIVE".equals(row.get("deletion_state"))
                || state.deleteEpoch() != number(row, "chapter_delete_epoch")) {
            return ErrorCode.CHAPTER_SOURCE_CHANGED.code();
        }
        return null;
    }

    private boolean stopping(State state) {
        return abortReason(state) != null || state.row().get("cancel_requested_at") != null
                || "CANCEL_REQUESTED".equals(state.row().get("status"));
    }

    private boolean owns(State state, Claim claim) {
        return state != null && !terminal(state.row()) && claim.ownerId() == number(state.row(), "owner_id")
                && claim.epoch() == number(state.row(), "dispatch_epoch")
                && claim.worker().equals(state.row().get("dispatch_claim_owner"))
                && Objects.equals(claim.claimedUntil(), instant(state.row(), "dispatch_claimed_until"))
                && claim.claimedUntil().isAfter(clock.instant());
    }

    private boolean requiresCompensation(State state) {
        // 已采用完成的正文不受旧派发结果影响，取消或失败任务则继续按原业务键阻止迟到工作。
        return state == null || (!"COMPLETED".equals(state.row().get("status")) && (terminal(state.row()) || stopping(state)));
    }

    private void abort(Map<String, Object> row, String code) {
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET dispatch_abort_error_code = COALESCE(dispatch_abort_error_code, ?),
                    current_stage = 'DISPATCH_CANCELLING' WHERE id = ?
                """, code, row.get("id"));
        if (row.get("dispatch_abort_error_code") == null) {
            row.put("dispatch_abort_error_code", code);
        }
    }

    private long pendingAttempts(Map<String, Object> row) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM novel_chapter_adaptation_attempt
                WHERE adaptation_id = ? AND owner_id = ? AND status IN ('REGISTERED', 'SEND_STARTED')
                """, Long.class, row.get("id"), row.get("owner_id"));
        return Objects.requireNonNull(count);
    }

    private void finish(Map<String, Object> row) {
        boolean cancelled = row.get("cancel_requested_at") != null || "CANCEL_REQUESTED".equals(row.get("status"));
        String status = cancelled ? "CANCELLED" : "FAILED";
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET status = ?, current_stage = ?, last_error_code = ?,
                    finished_at = ?, updated_at = ?, dispatch_claim_owner = NULL, dispatch_claimed_until = NULL,
                    version = version + 1 WHERE id = ?
                """, status, status, cancelled ? null : Objects.requireNonNullElse(row.get("dispatch_abort_error_code"), ErrorCode.ADAPTATION_DISPATCH_FAILED.code()),
                timestamp(clock.instant()), timestamp(clock.instant()), row.get("id"));
    }

    private void release(Map<String, Object> row, int seconds) {
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET next_dispatch_at = ?, dispatch_claim_owner = NULL,
                    dispatch_claimed_until = NULL, updated_at = ?, version = version + 1 WHERE id = ?
                """, timestamp(now.plusSeconds(seconds)), timestamp(now), row.get("id"));
    }

    private Map<String, Object> one(String sql, Object... parameters) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireIndependent() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Adaptation dispatch cannot join an ambient transaction");
        }
    }

    private static boolean terminal(Map<String, Object> row) {
        return AdaptationStatus.valueOf(row.get("status").toString()).terminal();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : UUID.fromString(row.get(key).toString());
    }

    private static Instant instant(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : ((Timestamp) row.get(key)).toInstant();
    }

    private static Timestamp timestamp(Instant time) {
        return Timestamp.from(time);
    }

    private record State(Map<String, Object> row, boolean shelfDeleted, long deleteEpoch) {
    }
}
