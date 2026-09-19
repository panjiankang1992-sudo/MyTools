package com.yuyutian.mytools.reader.repository.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationAttemptPayload;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.authorization.AttemptSettlementSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 单章受锁调用账本，预算、发送许可与一次性终态均在独立短事务中提交。 */
@Repository
public class AdaptationAttemptRepository {
    private final JdbcTemplate jdbc;
    private final AdaptationExecutionRepository executions;
    private final AttemptSettlementSigner signer;
    private final AdaptationAttemptPayload payloads;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper disclosureMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 注入数据库、已验证执行锁及专用签名器，不在事务内访问 Provider。 */
    @Autowired
    public AdaptationAttemptRepository(JdbcTemplate jdbc, AdaptationExecutionRepository executions, AttemptSettlementSigner signer,
                                        AdaptationAttemptPayload payloads, PlatformTransactionManager manager) {
        this(jdbc, executions, signer, payloads, manager, Clock.systemUTC());
    }

    /** 可控时钟覆盖预留、发送、迟到结算及恢复边界。 */
    public AdaptationAttemptRepository(JdbcTemplate jdbc, AdaptationExecutionRepository executions, AttemptSettlementSigner signer,
                                        AdaptationAttemptPayload payloads, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.signer = signer;
        this.payloads = payloads;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /** 每个阶段最多预留一次；同一执行的相同调用重放不会再扣预算。 */
    public AdaptationAttemptModels.Reservation reserve(WorkloadAuthorization authorization, AdaptationAttemptModels.Reserve input) {
        independent();
        requireCertificate(authorization);
        if (input == null || input.providerAttemptId() == null || input.callKind() == null) throw invalid();
        AdaptationText.requireSha256(input.requestSha256(), ErrorCode.ADAPTATION_REQUEST_INVALID);
        return atomic(() -> {
            Map<String, Object> parent = executions.lockedCurrent(authorization, false);
            Map<String, Object> existing = attempt(parent, input.providerAttemptId());
            String fingerprint = AdaptationText.fingerprint("adaptation-attempt-reservation-v1",
                    List.of(input.providerAttemptId().toString(), input.callKind().name(), input.requestSha256()));
            if (existing != null) {
                requireOriginal(authorization, existing);
                if (!fingerprint.equals(existing.get("reservation_sha256"))) throw conflict();
                return reservation(existing);
            }
            int number = Math.toIntExact(number(parent, "attempt_count") + 1);
            requirePhase(parent, number, input.callKind());
            // 未结算或失败的调用不能通过另一个随机 ID 绕过恢复要求。
            if (count("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND (status <> 'SUCCEEDED' OR archived_only = TRUE)",
                    parent.get("owner_id"), parent.get("id")) != 0 || count("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ?",
                    parent.get("owner_id"), parent.get("id")) != number - 1) throw fenced();
            if (number(parent, "provider_call_budget_remaining") < 1) throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
            long budget = Math.min(input.callKind().millis(), number(parent, "provider_millis_remaining"));
            budget = Math.min(budget, ChronoUnit.MILLIS.between(now(), instant(parent, "deadline_at")) - 5000);
            if (budget < 1) throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
            UUID id = UUID.randomUUID();
            Instant expires = earlier(now().plusSeconds(30), instant(parent, "deadline_at"));
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_attempt (id, adaptation_id, owner_id, attempt_no, call_kind,
                        task_instance_id, execution_id, fencing_token, provider_deployment_id, model_id, credential_generation,
                        provider_attempt_id, request_sha256, status, chapter_delete_epoch, repair_of_attempt_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REGISTERED', ?, ?, ?)
                    """, id.toString(), parent.get("id"), parent.get("owner_id"), number, input.callKind().name(),
                    authorization.taskInstanceId().toString(), authorization.executionId().toString(), authorization.fencingToken(),
                    parent.get("provider_deployment_id"), parent.get("model_id"), parent.get("credential_generation"),
                    input.providerAttemptId().toString(), input.requestSha256(), parent.get("chapter_delete_epoch"),
                    input.callKind() == Kind.REPAIR ? candidate(parent, 2) : null, stamp(now()));
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_attempt_reservation (attempt_id, adaptation_id, owner_id,
                        executor_thumbprint, reservation_sha256, reserved_millis, reservation_expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id.toString(), parent.get("id"), parent.get("owner_id"), authorization.certificateThumbprint(), fingerprint,
                    budget, stamp(expires), stamp(now()));
            int changed = jdbc.update("""
                    UPDATE novel_chapter_adaptation SET attempt_count = attempt_count + 1,
                        provider_call_budget_remaining = provider_call_budget_remaining - 1, provider_millis_remaining = provider_millis_remaining - ?,
                        repair_count = repair_count + ?, current_stage = ?, version = version + 1, updated_at = ?
                    WHERE owner_id = ? AND id = ? AND version = ? AND provider_call_budget_remaining > 0 AND provider_millis_remaining >= ?
                    """, budget, input.callKind() == Kind.REPAIR ? 1 : 0, input.callKind().name(), stamp(now()),
                    parent.get("owner_id"), parent.get("id"), parent.get("version"), budget);
            if (changed != 1) throw fenced();
            fresh(authorization);
            return reservation(attempt(parent, input.providerAttemptId()));
        });
    }

    /** 首次发送前原子复核同意、共享熔断和 fence；响应丢失重放不能再次获得发送权。 */
    public AdaptationAttemptModels.SendPermit sendStarted(WorkloadAuthorization authorization, UUID providerAttemptId) {
        independent();
        requireCertificate(authorization);
        return atomic(() -> {
            Map<String, Object> parent = executions.lockedCurrent(authorization, false);
            Map<String, Object> attempt = requiredAttempt(parent, providerAttemptId);
            requireOriginal(authorization, attempt);
            if (!"REGISTERED".equals(attempt.get("status"))) {
                if (attempt.get("settlement_key_id") == null || !instant(attempt, "settlement_expires_at").isAfter(now())) throw fenced();
                return sendPermit(attempt, false);
            }
            if (!instant(attempt, "reservation_expires_at").isAfter(now())) throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
            requireConsent(parent);
            lockCircuits(attempt, true);
            // 把等待上界完整预占且不退款，未知结果或失联不能重置总预算。
            Instant sendBy = earlier(now().plusSeconds(2), authorization.authorizedUntil());
            Instant callDeadline = earlier(now().plusMillis(number(attempt, "reserved_millis")), instant(parent, "deadline_at").minusSeconds(5));
            Instant expiry = callDeadline.plusSeconds(120);
            if (!callDeadline.isAfter(sendBy)) throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
            attempt.put("settlement_expires_at", stamp(expiry));
            var grant = signer.issue(scope(attempt));
            jdbc.update("""
                    UPDATE novel_chapter_adaptation_attempt_reservation SET send_by = ?, call_deadline_at = ?, settlement_key_id = ?, settlement_nonce = ?
                    WHERE owner_id = ? AND attempt_id = ? AND send_by IS NULL
                    """, stamp(sendBy), stamp(callDeadline), grant.keyId(), grant.nonce(), attempt.get("owner_id"), attempt.get("id"));
            int changed = jdbc.update("""
                    UPDATE novel_chapter_adaptation_attempt SET status = 'SEND_STARTED', transmission_count = 1, last_send_started_at = ?,
                        settlement_token_sha256 = ?, settlement_expires_at = ? WHERE owner_id = ? AND id = ? AND status = 'REGISTERED'
                    """, stamp(now()), grant.sha256(), stamp(expiry), attempt.get("owner_id"), attempt.get("id"));
            if (changed != 1) throw fenced();
            fresh(authorization);
            if (!sendBy.isAfter(now())) throw fenced();
            return sendPermit(requiredAttempt(parent, providerAttemptId), true);
        });
    }

    /** 原生 TLS 身份和窄令牌允许迟到留档；只有仍有效的当前执行授权才能产生可继续使用的结果。 */
    public AdaptationAttemptModels.Settlement settle(UUID adaptationId, UUID originalExecutionId, UUID providerAttemptId,
                                                     String certificateThumbprint, String settlementToken,
                                                     WorkloadAuthorization current, AdaptationAttemptModels.Terminal input) {
        independent();
        return atomic(() -> {
            Map<String, Object> parent = lockForSettlement(adaptationId);
            Map<String, Object> attempt = requiredAttempt(parent, providerAttemptId);
            if (number(parent, "chapter_delete_epoch") != number(attempt, "chapter_delete_epoch")) throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
            if (!originalExecutionId.toString().equals(attempt.get("execution_id")) || attempt.get("settlement_key_id") == null) throw fenced();
            signer.verify(settlementToken, text(attempt, "settlement_token_sha256"), scope(attempt), certificateThumbprint);
            var checked = payloads.check(Kind.valueOf(text(attempt, "call_kind")), input);
            if (!"SEND_STARTED".equals(attempt.get("status"))) {
                if (!checked.sha256().equals(attempt.get("terminal_payload_sha256"))) throw conflict();
                return settlement(attempt);
            }
            boolean archived = stopped(parent) || current == null || !isCurrent(current, parent, attempt, certificateThumbprint);
            writeTerminal(attempt, checked, archived);
            updateCircuits(attempt, checked.value());
            return settlement(requiredAttempt(parent, providerAttemptId));
        });
    }

    /** 已领取执行可收敛过期未决调用，不重发 Provider、不退还预算，也不自动采用结果。 */
    public int recover(WorkloadAuthorization authorization) {
        independent();
        return atomic(() -> {
            Map<String, Object> parent = executions.lockedCurrent(authorization, true);
            List<String> ids = jdbc.queryForList("""
                    SELECT provider_attempt_id FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ?
                        AND status IN ('REGISTERED', 'SEND_STARTED') ORDER BY attempt_no LIMIT 5
                    """, String.class, parent.get("owner_id"), parent.get("id"));
            int recovered = 0;
            String reason = null;
            for (String id : ids) {
                Map<String, Object> attempt = requiredAttempt(parent, UUID.fromString(id));
                boolean registered = "REGISTERED".equals(attempt.get("status"));
                boolean changedExecution = !authorization.executionId().toString().equals(attempt.get("execution_id"))
                        || authorization.fencingToken() != number(attempt, "fencing_token");
                // 已发送调用保留有界结算窗口，未知结果分类之后迟到输出不再覆盖终态。
                if (registered ? !stopped(parent) && !changedExecution && instant(attempt, "reservation_expires_at").isAfter(now())
                        : instant(attempt, "settlement_expires_at").isAfter(now())) continue;
                String code = expire(attempt);
                if (reason == null) reason = code;
                recovered++;
            }
            if (recovered > 0) requestRecoveryStop(parent, reason);
            fresh(authorization);
            return recovered;
        });
    }

    /** 后台仅发现到达绝对结算期限的身份，不读取正文，也不依赖当前执行授权或创建开关。 */
    public List<UUID> expiredAdaptationIds() {
        return expiredAdaptationIds(null);
    }

    /** 按稳定身份游标扫描，遇到持续故障或忙碌范围仍能让后续批次获得处理机会。 */
    public List<UUID> expiredAdaptationIds(UUID after) {
        independent();
        return atomic(() -> jdbc.queryForList("""
                SELECT DISTINCT a.adaptation_id FROM novel_chapter_adaptation_attempt a
                JOIN novel_chapter_adaptation_attempt_reservation r
                    ON r.attempt_id = a.id AND r.adaptation_id = a.adaptation_id AND r.owner_id = a.owner_id
                WHERE ((a.status = 'REGISTERED' AND r.reservation_expires_at <= ?)
                    OR (a.status = 'SEND_STARTED' AND a.settlement_expires_at <= ?)) AND a.adaptation_id > ?
                ORDER BY a.adaptation_id
                LIMIT 16
                """, String.class, stamp(now()), stamp(now()), after == null ? "" : after.toString()).stream().map(UUID::fromString).toList());
    }

    /** 独立短事务收敛一个范围；删除或换代不阻止无正文归档，但不能借此产生发送、采用或任务终态。 */
    public int recoverExpired(UUID adaptationId) {
        independent();
        if (adaptationId == null) throw invalid();
        return atomic(() -> {
            var parent = lockForRecovery(adaptationId);
            // 与派发、发送、删除和窄结算使用相同锁顺序；忙碌范围交给下一轮重试。
            if (parent == null) return 0;
            List<String> ids = jdbc.queryForList("""
                    SELECT provider_attempt_id FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ?
                        AND status IN ('REGISTERED', 'SEND_STARTED') ORDER BY attempt_no LIMIT 5
                    """, String.class, parent.get("owner_id"), parent.get("id"));
            int recovered = 0;
            String reason = null;
            for (String id : ids) {
                var attempt = requiredAttempt(parent, UUID.fromString(id));
                // 已发送调用即使被撤销、删除或接管也保留完整窄结算窗口。
                String expiry = "REGISTERED".equals(attempt.get("status")) ? "reservation_expires_at" : "settlement_expires_at";
                if (instant(attempt, expiry).isAfter(now())) continue;
                String code = expire(attempt);
                if (reason == null) reason = code;
                recovered++;
            }
            if (recovered > 0) requestRecoveryStop(parent, reason);
            return recovered;
        });
    }

    private void requestRecoveryStop(Map<String, Object> parent, String reason) {
        if (AdaptationStatus.valueOf(text(parent, "status")).terminal()) return;
        // 与归档同事务发出停止信号，避免执行器在归档后、上报失败前退出留下无待扫调用的任务。
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET dispatch_abort_error_code = COALESCE(dispatch_abort_error_code, ?),
                    current_stage = 'DISPATCH_CANCELLING', next_dispatch_at = CASE WHEN next_dispatch_at > ? THEN ? ELSE next_dispatch_at END,
                    updated_at = ?, version = version + 1 WHERE owner_id = ? AND id = ?
                """, reason, stamp(now()), stamp(now()), stamp(now()), parent.get("owner_id"), parent.get("id"));
    }

    private String expire(Map<String, Object> attempt) {
        boolean registered = "REGISTERED".equals(attempt.get("status"));
        String code = (registered ? ErrorCode.ADAPTATION_EXECUTION_FENCED : ErrorCode.ADAPTATION_PROVIDER_OUTCOME_UNKNOWN).code();
        var terminal = new AdaptationAttemptModels.Terminal(registered ? "FAILED" : "CALL_OUTCOME_UNKNOWN", null, null, null,
                null, null, null, null, code, null);
        writeTerminal(attempt, payloads.check(Kind.valueOf(text(attempt, "call_kind")), terminal), true);
        if (!registered) updateCircuits(attempt, terminal);
        return code;
    }

    private Map<String, Object> lockForRecovery(UUID adaptationId) {
        var known = one("SELECT owner_id, shelf_book_id, content_binding_id, chapter_id FROM novel_chapter_adaptation WHERE id = ?", adaptationId.toString());
        if (known == null) return null;
        // 逻辑删除保留外键范围，恢复只结算空载荷，不恢复已删除内容的可读性或执行资格。
        if (one("SELECT id FROM shelf_book WHERE owner_id = ? AND id = ? FOR UPDATE SKIP LOCKED",
                known.get("owner_id"), known.get("shelf_book_id")) == null) return null;
        if (one("SELECT id FROM shelf_book_content_binding WHERE owner_id = ? AND id = ? FOR UPDATE SKIP LOCKED",
                known.get("owner_id"), known.get("content_binding_id")) == null) return null;
        if (one("SELECT id FROM shelf_book_chapter WHERE owner_id = ? AND id = ? FOR UPDATE SKIP LOCKED",
                known.get("owner_id"), known.get("chapter_id")) == null) return null;
        return one("SELECT id, owner_id, status FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ? FOR UPDATE SKIP LOCKED",
                known.get("owner_id"), adaptationId.toString());
    }

    private void requirePhase(Map<String, Object> parent, int number, Kind kind) {
        if (!"SEALED".equals(parent.get("context_status")) || number < 1 || number > 5) throw fenced();
        Kind expected = switch (number) { case 1 -> Kind.PLAN; case 2 -> Kind.GENERATE; case 4 -> Kind.REPAIR; default -> Kind.CRITIC; };
        String state = switch (kind) { case PLAN -> "ANALYZING"; case GENERATE -> "GENERATING"; case CRITIC -> "VALIDATING"; case REPAIR -> "REPAIRING"; };
        if (kind != expected || !state.equals(parent.get("status"))) throw fenced();
        if (kind != Kind.PLAN && count("SELECT COUNT(*) FROM novel_chapter_adaptation_constraint_set WHERE owner_id = ? AND adaptation_id = ?",
                parent.get("owner_id"), parent.get("id")) != 1) throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        if (kind == Kind.REPAIR && count("""
                SELECT COUNT(*) FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND adaptation_id = ?
                    AND validation_round = 1 AND outcome = 'REPAIRABLE' AND candidate_attempt_id = ?
                """, parent.get("owner_id"), parent.get("id"), candidate(parent, 2)) != 1) throw fenced();
    }

    /** 在 HTTP 消费正文之前验证窄能力；实际写入事务仍会再次核对签名、删除和有效期。 */
    public void verifySettlement(UUID adaptationId, UUID originalExecutionId, UUID providerAttemptId, String certificate, String token) {
        independent();
        atomic(() -> {
            var parent = lockForSettlement(adaptationId);
            var attempt = requiredAttempt(parent, providerAttemptId);
            if (number(parent, "chapter_delete_epoch") != number(attempt, "chapter_delete_epoch")) throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
            if (!originalExecutionId.toString().equals(attempt.get("execution_id")) || attempt.get("settlement_key_id") == null) throw fenced();
            signer.verify(token, text(attempt, "settlement_token_sha256"), scope(attempt), certificate);
            return null;
        });
    }

    private String candidate(Map<String, Object> parent, int number) {
        Map<String, Object> row = one("SELECT id FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND attempt_no = ? AND status = 'SUCCEEDED' AND archived_only = FALSE",
                parent.get("owner_id"), parent.get("id"), number);
        if (row == null) throw fenced();
        return text(row, "id");
    }

    private void requireConsent(Map<String, Object> parent) {
        // 仅新流程显式标记的请求免除额外授权；旧任务仍尊重历史撤销。
        if ("DIRECT".equals(parent.get("consent_policy"))) {
            var deployment = one("SELECT enabled FROM novel_adaptation_provider_deployment WHERE id = ? FOR UPDATE",
                    parent.get("provider_deployment_id"));
            // 免额外授权不等于允许向已经停用的模型部署发送数据。
            if (deployment == null || !Boolean.TRUE.equals(deployment.get("enabled"))) {
                throw failure(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
            }
            return;
        }
        // 与撤销共用授权修订行；再次同意也不能让旧修订创建的任务重新取得发送权。
        AdaptationConsentGate.require(jdbc, disclosureMapper, number(parent, "owner_id"), parent.get("disclosure_version"),
                parent.get("provider_deployment_id"), number(parent, "consent_revision"));
    }

    private List<Map<String, Object>> lockCircuits(Map<String, Object> attempt, boolean requireClosed) {
        var auth = circuit(attempt, "AUTH", Long.toString(number(attempt, "credential_generation")));
        var availability = circuit(attempt, "AVAILABILITY", binaryText(attempt, "model_id"));
        if (requireClosed && (!"CLOSED".equals(auth.get("state")) || !"CLOSED".equals(availability.get("state")))) throw failure(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
        return List.of(auth, availability);
    }

    private Map<String, Object> circuit(Map<String, Object> attempt, String kind, String scope) {
        Map<String, Object> row = one("""
                SELECT * FROM novel_adaptation_provider_circuit WHERE bucket_kind = ? AND provider_deployment_id = ? AND scope_key = ? FOR UPDATE
                """, kind, attempt.get("provider_deployment_id"), scope.getBytes(StandardCharsets.UTF_8));
        // 发布合约尚未登记共享桶时拒绝发送，不能自动把未知 Provider 设为健康。
        if (row == null) throw failure(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
        return row;
    }

    private void updateCircuits(Map<String, Object> attempt, AdaptationAttemptModels.Terminal terminal) {
        var buckets = lockCircuits(attempt, false);
        if ("READER_040".equals(terminal.errorCode())) openCircuit(buckets.getFirst(), 1, 300);
        if (List.of("READER_039", "READER_041", "READER_054").contains(terminal.errorCode() == null ? "" : terminal.errorCode())) {
            Map<String, Object> bucket = buckets.get(1);
            if (!"CLOSED".equals(bucket.get("state"))) return;
            boolean sameWindow = bucket.get("window_started_at") != null && instant(bucket, "window_started_at").plusSeconds(60).isAfter(now());
            int failures = sameWindow ? Math.toIntExact(number(bucket, "failure_count") + 1) : 1;
            if (failures >= 3) openCircuit(bucket, failures, 60);
            else jdbc.update("UPDATE novel_adaptation_provider_circuit SET failure_count = ?, window_started_at = ?, version = version + 1 WHERE bucket_id = ?",
                    failures, sameWindow ? bucket.get("window_started_at") : stamp(now()), bucket.get("bucket_id"));
        }
    }

    private void openCircuit(Map<String, Object> bucket, int failures, int cooldown) {
        if (!"CLOSED".equals(bucket.get("state"))) return;
        jdbc.update("""
                UPDATE novel_adaptation_provider_circuit SET state = 'OPEN', failure_count = ?, opened_at = ?, next_probe_at = ?, version = version + 1
                WHERE bucket_id = ? AND state = 'CLOSED'
                """, failures, stamp(now()), stamp(now().plusSeconds(cooldown)), bucket.get("bucket_id"));
    }

    private Map<String, Object> lockForSettlement(UUID adaptationId) {
        Map<String, Object> known = one("SELECT owner_id, shelf_book_id, content_binding_id, chapter_id FROM novel_chapter_adaptation WHERE id = ?", adaptationId.toString());
        if (known == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        Map<String, Object> shelf = one("SELECT deleted FROM shelf_book WHERE owner_id = ? AND id = ? FOR UPDATE", known.get("owner_id"), known.get("shelf_book_id"));
        one("SELECT id FROM shelf_book_content_binding WHERE owner_id = ? AND id = ? FOR UPDATE", known.get("owner_id"), known.get("content_binding_id"));
        Map<String, Object> chapter = one("SELECT adaptation_delete_epoch FROM shelf_book_chapter WHERE owner_id = ? AND id = ? FOR UPDATE", known.get("owner_id"), known.get("chapter_id"));
        Map<String, Object> parent = one("SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ? FOR UPDATE", known.get("owner_id"), adaptationId.toString());
        if (parent == null || shelf == null || Boolean.TRUE.equals(shelf.get("deleted")) || chapter == null || !"LIVE".equals(parent.get("deletion_state"))
                || number(chapter, "adaptation_delete_epoch") != number(parent, "chapter_delete_epoch")) throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        return parent;
    }

    private boolean isCurrent(WorkloadAuthorization current, Map<String, Object> parent, Map<String, Object> attempt, String certificate) {
        return current.authorizedUntil() != null && current.authorizedUntil().isAfter(now())
                && current.resource() == com.yuyutian.mytools.reader.model.adaptation.WorkloadResource.CHAPTER_ADAPTATION
                && current.resourceId().toString().equals(parent.get("id")) && certificate.equals(current.certificateThumbprint())
                && current.taskInstanceId().toString().equals(parent.get("task_instance_id"))
                && current.taskInstanceId().toString().equals(attempt.get("task_instance_id"))
                && current.executionId().toString().equals(parent.get("current_execution_id"))
                && current.executionId().toString().equals(attempt.get("execution_id"))
                && current.fencingToken() == number(parent, "fencing_token") && current.fencingToken() == number(attempt, "fencing_token");
    }

    private void writeTerminal(Map<String, Object> attempt, AdaptationAttemptPayload.Checked checked, boolean archived) {
        var value = checked.value();
        int changed = jdbc.update("""
                UPDATE novel_chapter_adaptation_attempt SET status = ?, output_text = ?, output_sha256 = ?, output_codepoint_count = ?,
                    plan_json = ?, plan_sha256 = ?, finish_reason = ?, provider_request_id = ?, input_tokens = ?, output_tokens = ?, http_status = ?,
                    error_code = ?, diagnostic_sha256 = ?, terminal_payload_sha256 = ?, viewable_candidate = ?, archived_only = ?, completed_at = ?
                WHERE owner_id = ? AND id = ? AND status IN ('REGISTERED', 'SEND_STARTED') AND terminal_payload_sha256 IS NULL
                """, value.status(), value.outputText(), checked.outputSha256(), value.outputText() == null ? null : checked.codepoints(),
                value.structuredJson(), checked.structuredSha256(), value.finishReason(), value.providerRequestId(), value.inputTokens(), value.outputTokens(),
                value.httpStatus(), value.errorCode(), value.diagnosticSha256(), checked.sha256(), checked.candidate(), archived, stamp(now()), attempt.get("owner_id"), attempt.get("id"));
        if (changed != 1) throw conflict();
    }

    private AdaptationAttemptModels.SendPermit sendPermit(Map<String, Object> attempt, boolean maySend) {
        var grant = signer.recreate(scope(attempt), text(attempt, "settlement_key_id"), text(attempt, "settlement_nonce"));
        if (!grant.sha256().equals(attempt.get("settlement_token_sha256"))) throw fenced();
        return new AdaptationAttemptModels.SendPermit(UUID.fromString(text(attempt, "provider_attempt_id")), maySend,
                Math.toIntExact(number(attempt, "transmission_count")), instant(attempt, "send_by"), instant(attempt, "call_deadline_at"),
                instant(attempt, "settlement_expires_at"), grant.token());
    }
    private static AttemptSettlementSigner.Scope scope(Map<String, Object> attempt) {
        return new AttemptSettlementSigner.Scope(text(attempt, "adaptation_id"), text(attempt, "id"), text(attempt, "provider_attempt_id"),
                text(attempt, "request_sha256"), binaryText(attempt, "provider_deployment_id"), text(attempt, "task_instance_id"),
                text(attempt, "execution_id"), number(attempt, "fencing_token"), number(attempt, "chapter_delete_epoch"),
                text(attempt, "executor_thumbprint"), instant(attempt, "settlement_expires_at"));
    }
    private static AdaptationAttemptModels.Reservation reservation(Map<String, Object> attempt) {
        return new AdaptationAttemptModels.Reservation(UUID.fromString(text(attempt, "id")), UUID.fromString(text(attempt, "provider_attempt_id")),
                Math.toIntExact(number(attempt, "attempt_no")), Kind.valueOf(text(attempt, "call_kind")), text(attempt, "status"),
                number(attempt, "reserved_millis"), instant(attempt, "reservation_expires_at"));
    }
    private static AdaptationAttemptModels.Settlement settlement(Map<String, Object> attempt) {
        return new AdaptationAttemptModels.Settlement(UUID.fromString(text(attempt, "id")), UUID.fromString(text(attempt, "provider_attempt_id")),
                text(attempt, "status"), Boolean.TRUE.equals(attempt.get("archived_only")), text(attempt, "terminal_payload_sha256"));
    }
    private Map<String, Object> requiredAttempt(Map<String, Object> parent, UUID providerId) {
        Map<String, Object> found = attempt(parent, providerId);
        if (found == null) throw failure(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND);
        return found;
    }
    private Map<String, Object> attempt(Map<String, Object> parent, UUID providerId) {
        if (providerId == null) throw invalid();
        return one("""
                SELECT a.*, r.executor_thumbprint, r.reservation_sha256, r.reserved_millis, r.reservation_expires_at,
                    r.send_by, r.call_deadline_at, r.settlement_key_id, r.settlement_nonce
                FROM novel_chapter_adaptation_attempt a JOIN novel_chapter_adaptation_attempt_reservation r
                    ON r.attempt_id = a.id AND r.adaptation_id = a.adaptation_id AND r.owner_id = a.owner_id
                WHERE a.owner_id = ? AND a.adaptation_id = ? AND a.provider_attempt_id = ? FOR UPDATE
                """, parent.get("owner_id"), parent.get("id"), providerId.toString());
    }
    private static void requireOriginal(WorkloadAuthorization authorization, Map<String, Object> attempt) {
        if (!authorization.taskInstanceId().toString().equals(attempt.get("task_instance_id"))
                || !authorization.executionId().toString().equals(attempt.get("execution_id"))
                || authorization.fencingToken() != number(attempt, "fencing_token")
                || !authorization.certificateThumbprint().equals(attempt.get("executor_thumbprint"))) throw fenced();
    }
    private static void requireCertificate(WorkloadAuthorization authorization) {
        if (authorization == null || authorization.certificateThumbprint() == null || !authorization.certificateThumbprint().matches("[A-Za-z0-9_-]{43}")) throw fenced();
    }
    private boolean stopped(Map<String, Object> parent) {
        return parent.get("cancel_requested_at") != null || parent.get("dispatch_abort_error_code") != null
                || AdaptationStatus.valueOf(text(parent, "status")).terminal() || "CANCEL_REQUESTED".equals(parent.get("status"))
                || !instant(parent, "deadline_at").isAfter(now());
    }
    private void fresh(WorkloadAuthorization authorization) { if (!authorization.authorizedUntil().isAfter(now())) throw fenced(); }
    private <T> T atomic(Supplier<T> operation) {
        try { return transaction.execute(ignored -> operation.get()); }
        catch (DuplicateKeyException exception) { throw conflict(); }
        catch (DataAccessException exception) {
            // SQL 诊断可能包含完整输出、令牌摘要或参数，统一抛弃底层异常链。
            throw failure(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE);
        }
    }
    private static void independent() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw fenced(); }
    private Map<String, Object> one(String sql, Object... args) { var rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    private long count(String sql, Object... args) { Long count = jdbc.queryForObject(sql, Long.class, args); return count == null ? 0 : count; }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static String text(Map<String, Object> row, String key) { return (String) row.get(key); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Instant instant(Map<String, Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static String binaryText(Map<String, Object> row, String key) { return new String((byte[]) row.get(key), StandardCharsets.UTF_8); }
    private static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }
    private static ChapterAdaptationException invalid() { return failure(ErrorCode.ADAPTATION_REQUEST_INVALID); }
    private static ChapterAdaptationException fenced() { return failure(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private static ChapterAdaptationException conflict() { return failure(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT); }
    private static ChapterAdaptationException failure(ErrorCode code) { return new ChapterAdaptationException(code); }
}
