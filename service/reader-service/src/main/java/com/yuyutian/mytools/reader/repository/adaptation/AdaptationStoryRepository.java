package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationAttemptPayload;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationStoryEngine;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationStateMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 约束、校验与采用的唯一事务入口；输入只能引用已封存的调用，不能提交替代正文或报告。 */
@Repository
public class AdaptationStoryRepository {
    private static final Set<String> FAILURES = Set.of("READER_033", "READER_039", "READER_040", "READER_041", "READER_042", "READER_043",
            "READER_048", "READER_049", "READER_052", "READER_053", "READER_054", "READER_055", "READER_058");
    private final JdbcTemplate jdbc;
    private final AdaptationExecutionRepository executions;
    private final AdaptationContextRepository contexts;
    private final AdaptationStoryEngine engine;
    private final AdaptationAttemptPayload payloads;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** 注入已有身份守卫和纯本地规则引擎，持锁过程不调用外部服务。 */
    @Autowired
    public AdaptationStoryRepository(JdbcTemplate jdbc, AdaptationExecutionRepository executions, AdaptationContextRepository contexts,
                                     AdaptationStoryEngine engine, AdaptationAttemptPayload payloads, ObjectMapper mapper, PlatformTransactionManager manager) {
        this(jdbc, executions, contexts, engine, payloads, mapper, manager, Clock.systemUTC());
    }

    /** 注入受控时钟以验证取消、超期和接管窗口。 */
    public AdaptationStoryRepository(JdbcTemplate jdbc, AdaptationExecutionRepository executions, AdaptationContextRepository contexts,
                                     AdaptationStoryEngine engine, AdaptationAttemptPayload payloads, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.executions = executions;
        this.contexts = contexts;
        this.engine = engine;
        this.payloads = payloads;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /** 用已成功 PLAN 编译一次性约束，封存与进入 GENERATING 在同事务完成。 */
    public AdaptationStoryModels.Constraints seal(WorkloadAuthorization authorization, UUID planAttemptId) {
        return atomic(() -> {
            var parent = current(authorization, false);
            nonePending(parent);
            var plan = attempt(parent, planAttemptId, "PLAN", 1);
            var snapshot = contexts.sealedForClaim(parent);
            var stored = one("SELECT * FROM novel_chapter_adaptation_constraint_set WHERE owner_id = ? AND adaptation_id = ?", parent.get("owner_id"), parent.get("id"));
            if (stored != null) {
                if (!planAttemptId.toString().equals(stored.get("plan_attempt_id"))) throw conflict();
                return constraints(parent, stored);
            }
            if (!"ANALYZING".equals(parent.get("status")) || number(parent, "attempt_count") != 1) throw fenced();
            var compiled = engine.compile(snapshot, jsonText(plan.get("plan_json")), text(parent, "constraint_version"));
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_constraint_set (adaptation_id, owner_id, plan_attempt_id, deterministic_json,
                        supplement_json, merged_json, deterministic_sha256, supplement_sha256, merged_sha256, constraint_version, created_by_execution_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, parent.get("id"), parent.get("owner_id"), planAttemptId.toString(), compiled.deterministicJson(), compiled.supplementJson(), compiled.mergedJson(),
                    compiled.deterministicSha256(), compiled.supplementSha256(), compiled.mergedSha256(), text(parent, "constraint_version"), authorization.executionId().toString(), stamp());
            transition(parent, AdaptationStatus.GENERATING, "GENERATE_PENDING", guards(parent, true, false, false, false, false), null);
            fresh(authorization, parent);
            return compiled;
        });
    }

    /** 只开放候选已保存到校验阶段的固定边，不能通过此接口跳过任何证据。 */
    public AdaptationStoryModels.Progress progress(WorkloadAuthorization authorization, String expectedStatus, String expectedStage,
                                                   String nextStatus, String nextStage) {
        boolean generation = "GENERATING".equals(expectedStatus) && "GENERATE".equals(expectedStage);
        boolean repair = "REPAIRING".equals(expectedStatus) && "REPAIR".equals(expectedStage);
        if ((!generation && !repair) || !"VALIDATING".equals(nextStatus) || !"CRITIC_PENDING".equals(nextStage)) throw fenced();
        return atomic(() -> {
            var parent = current(authorization, false);
            nonePending(parent);
            constraints(parent, requiredConstraints(parent));
            int number = generation ? 2 : 4;
            var candidate = one("SELECT id FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND attempt_no = ?",
                    parent.get("owner_id"), parent.get("id"), number);
            if (candidate == null || number(parent, "attempt_count") != number) throw fenced();
            attempt(parent, UUID.fromString(text(candidate, "id")), generation ? "GENERATE" : "REPAIR", number);
            if (nextStatus.equals(parent.get("status")) && nextStage.equals(parent.get("current_stage"))) return view(parent);
            if (!expectedStatus.equals(parent.get("status")) || !expectedStage.equals(parent.get("current_stage"))) throw fenced();
            transition(parent, AdaptationStatus.VALIDATING, nextStage, guards(parent, true, true, false, false, false), null);
            fresh(authorization, parent);
            return view(reload(parent));
        });
    }

    /** 不接受模型自报最终 PASS，Reader 重算确定性检查并和同候选的 critic 合并后保存。 */
    public AdaptationStoryModels.Validation validate(WorkloadAuthorization authorization, UUID candidateId, UUID criticId) {
        return atomic(() -> {
            var parent = current(authorization, true);
            nonePending(parent);
            var candidate = candidate(parent, candidateId);
            int round = number(candidate, "attempt_no") == 2 ? 1 : 2;
            var critic = attempt(parent, criticId, "CRITIC", round == 1 ? 3 : 5);
            var constraints = constraints(parent, requiredConstraints(parent));
            var evaluation = engine.evaluate(contexts.sealedForClaim(parent), constraints, candidateId, text(candidate, "output_text"), criticId, jsonText(critic.get("plan_json")), round);
            var prior = one("SELECT * FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND adaptation_id = ? AND candidate_attempt_id = ? AND validation_round = ?",
                    parent.get("owner_id"), parent.get("id"), candidateId.toString(), round);
            if (prior != null) {
                verifyValidation(parent, prior, criticId, evaluation, round);
                return validation(prior, parent);
            }
            if (!"VALIDATING".equals(parent.get("status")) || number(parent, "attempt_count") != (round == 1 ? 3 : 5)) throw fenced();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_validation (id, adaptation_id, owner_id, candidate_attempt_id, critic_attempt_id,
                        validation_round, deterministic_version, deterministic_json, critic_version, critic_json, outcome,
                        report_json, report_sha256, created_at, content_policy_outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id.toString(), parent.get("id"), parent.get("owner_id"), candidateId.toString(), criticId.toString(), round,
                    text(parent, "constraint_version"), evaluation.deterministicJson(), AdaptationStoryEngine.CRITIC_VERSION, evaluation.criticJson(), evaluation.outcome(),
                    evaluation.reportJson(), evaluation.reportSha256(), stamp(), evaluation.contentPolicyOutcome());
            boolean pass = "PASS".equals(evaluation.outcome());
            boolean repairable = "REPAIRABLE".equals(evaluation.outcome());
            AdaptationStatus target = pass ? AdaptationStatus.PERSISTING : repairable ? AdaptationStatus.REPAIRING : AdaptationStatus.FAILED;
            String error = target != AdaptationStatus.FAILED ? null : "BLOCKED".equals(evaluation.contentPolicyOutcome()) ? "READER_053" : "READER_043";
            transition(parent, target, pass ? "PERSISTING" : repairable ? "REPAIR_PENDING" : "FAILED",
                    guards(parent, true, true, pass, repairable, false), error);
            fresh(authorization, parent);
            return validation(one("SELECT * FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND id = ?", parent.get("owner_id"), id.toString()), reload(parent));
        });
    }

    /** PASS 校验、候选和 selection 在同一锁定范围复核，正式完成不能由旧 fence 回写。 */
    public AdaptationStoryModels.Selection complete(WorkloadAuthorization authorization, UUID candidateId, UUID validationId) {
        return atomic(() -> {
            var parent = current(authorization, true);
            nonePending(parent);
            var candidate = candidate(parent, candidateId);
            var stored = one("SELECT * FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND adaptation_id = ? AND id = ? AND candidate_attempt_id = ?",
                    parent.get("owner_id"), parent.get("id"), validationId.toString(), candidateId.toString());
            if (stored == null) throw fenced();
            int round = number(candidate, "attempt_no") == 2 ? 1 : 2;
            UUID criticId = UUID.fromString(text(stored, "critic_attempt_id"));
            var critic = attempt(parent, criticId, "CRITIC", round == 1 ? 3 : 5);
            var evaluation = engine.evaluate(contexts.sealedForClaim(parent), constraints(parent, requiredConstraints(parent)), candidateId,
                    text(candidate, "output_text"), criticId, jsonText(critic.get("plan_json")), round);
            verifyValidation(parent, stored, criticId, evaluation, round);
            if (!"PASS".equals(evaluation.outcome()) || !"PASS".equals(evaluation.contentPolicyOutcome())) throw fenced();
            var selected = one("SELECT * FROM novel_chapter_adaptation_selection WHERE owner_id = ? AND adaptation_id = ?", parent.get("owner_id"), parent.get("id"));
            if (selected != null) {
                if (!"COMPLETED".equals(parent.get("status")) || !candidateId.toString().equals(selected.get("candidate_attempt_id"))
                        || !validationId.toString().equals(selected.get("validation_id"))) throw conflict();
                return new AdaptationStoryModels.Selection(candidateId, validationId, view(parent));
            }
            if (!"PERSISTING".equals(parent.get("status"))) throw fenced();
            jdbc.update("INSERT INTO novel_chapter_adaptation_selection VALUES (?, ?, ?, ?, ?)", parent.get("id"), parent.get("owner_id"), candidateId.toString(), validationId.toString(), stamp());
            transition(parent, AdaptationStatus.COMPLETED, "DONE", guards(parent, true, true, true, false, true), null);
            fresh(authorization, parent);
            return new AdaptationStoryModels.Selection(candidateId, validationId, view(reload(parent)));
        });
    }

    /** 只有无未决调用时才能结束普通失败；取消请求必须由取消恢复流程收敛。 */
    public AdaptationStoryModels.Progress fail(WorkloadAuthorization authorization, String errorCode) {
        if (errorCode == null || !FAILURES.contains(errorCode)) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_REQUEST_INVALID);
        return atomic(() -> {
            var parent = executions.lockedCurrent(authorization, true);
            nonePending(parent);
            if ("FAILED".equals(parent.get("status")) && errorCode.equals(parent.get("last_error_code"))) return view(parent);
            if ("CANCEL_REQUESTED".equals(parent.get("status")) || parent.get("cancel_requested_at") != null || parent.get("dispatch_abort_error_code") != null) throw fenced();
            String authoritative = instant(parent, "deadline_at").isAfter(clock.instant()) ? errorCode : "READER_058";
            transition(parent, AdaptationStatus.FAILED, "FAILED", guards(parent, false, false, false, false, false), authoritative);
            if (!authorization.authorizedUntil().isAfter(clock.instant())) throw fenced();
            return view(reload(parent));
        });
    }

    /** 从当前锁定执行恢复已完成证据，不创建调用、不修改预算，也不把留档候选交给新生成。 */
    public AdaptationStoryModels.Workflow workflow(WorkloadAuthorization authorization) {
        return atomic(() -> {
            var parent = executions.lockedCurrent(authorization, true);
            var state = executions.state(parent);
            String error = text(parent, "last_error_code");
            if (error != null && !error.matches("READER_[0-9]{3}")) throw incomplete();
            if (state.stopRequested()) {
                // 已停止窗口只提供收敛状态，不能借恢复入口继续读取原文或候选。
                if (!authorization.authorizedUntil().isAfter(clock.instant())) throw fenced();
                return new AdaptationStoryModels.Workflow(state, error, List.of(), null, null);
            }
            requireConstraintVersion(parent.get("constraint_version"));
            var rows = jdbc.queryForList("SELECT * FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? ORDER BY attempt_no LIMIT 6",
                    parent.get("owner_id"), parent.get("id"));
            if (rows.size() > 5 || rows.size() != number(parent, "attempt_count")) throw incomplete();
            List<AdaptationStoryModels.Attempt> attempts = new ArrayList<>();
            String[] kinds = {"PLAN", "GENERATE", "CRITIC", "REPAIR", "CRITIC"};
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                String kind = kinds[index];
                String status = text(row, "status");
                boolean archived = Boolean.TRUE.equals(row.get("archived_only"));
                if (number(row, "attempt_no") != index + 1 || !kind.equals(row.get("call_kind"))
                        || !Set.of("REGISTERED", "SEND_STARTED", "SUCCEEDED", "FAILED", "CALL_OUTCOME_UNKNOWN").contains(status)) throw incomplete();
                requireAttemptIdentity(parent, row);
                String output = null;
                String structured = null;
                String failure = text(row, "error_code");
                if (failure != null && !failure.matches("READER_[0-9]{3}")) throw incomplete();
                if ("SUCCEEDED".equals(status) && !archived) {
                    // 唯一可复用正文先经过已有完整终态摘要校验，不能只相信 SUCCEEDED 字符串。
                    var checked = attempt(parent, UUID.fromString(text(row, "id")), kind, index + 1);
                    output = text(checked, "output_text");
                    structured = checked.get("plan_json") == null ? null : jsonText(checked.get("plan_json"));
                }
                attempts.add(new AdaptationStoryModels.Attempt(UUID.fromString(text(row, "id")), UUID.fromString(text(row, "provider_attempt_id")),
                        index + 1, kind, status, archived, failure, output, structured));
            }
            var storedRules = one("SELECT * FROM novel_chapter_adaptation_constraint_set WHERE owner_id = ? AND adaptation_id = ?", parent.get("owner_id"), parent.get("id"));
            AdaptationStoryModels.Constraints rules = storedRules == null ? null : constraints(parent, storedRules);
            if (!Set.of("QUEUED", "CONTEXT_FREEZING", "ANALYZING").contains(state.status().name()) && rules == null) throw incomplete();
            var validations = jdbc.queryForList("SELECT * FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND adaptation_id = ? ORDER BY validation_round DESC LIMIT 3",
                    parent.get("owner_id"), parent.get("id"));
            if (validations.size() > 2 || !validations.isEmpty() && rules == null) throw incomplete();
            AdaptationStoryModels.Review review = null;
            for (var validation : validations) {
                UUID candidateId = UUID.fromString(text(validation, "candidate_attempt_id"));
                UUID criticId = UUID.fromString(text(validation, "critic_attempt_id"));
                var candidate = candidate(parent, candidateId);
                int round = number(candidate, "attempt_no") == 2 ? 1 : 2;
                var critic = attempt(parent, criticId, "CRITIC", round == 1 ? 3 : 5);
                var evaluation = engine.evaluate(contexts.sealedForClaim(parent), rules, candidateId,
                        text(candidate, "output_text"), criticId, jsonText(critic.get("plan_json")), round);
                verifyValidation(parent, validation, criticId, evaluation, round);
                if ("BLOCKED".equals(evaluation.outcome()) || !"PASS".equals(evaluation.contentPolicyOutcome())) throw incomplete();
                if (review == null) review = new AdaptationStoryModels.Review(UUID.fromString(text(validation, "id")), candidateId, criticId, round,
                        evaluation.outcome(), evaluation.contentPolicyOutcome(), evaluation.deterministicJson(), evaluation.criticJson(),
                        evaluation.reportJson(), evaluation.reportSha256());
            }
            fresh(authorization, parent);
            return new AdaptationStoryModels.Workflow(state, error, attempts, rules, review);
        });
    }

    private Map<String, Object> current(WorkloadAuthorization authorization, boolean allowTerminalReplay) {
        var parent = executions.lockedCurrent(authorization, true);
        if (parent.get("cancel_requested_at") != null || parent.get("dispatch_abort_error_code") != null || "CANCEL_REQUESTED".equals(parent.get("status"))
                || "CANCELLED".equals(parent.get("status")) || !allowTerminalReplay && AdaptationStatus.valueOf(text(parent, "status")).terminal()) throw fenced();
        requireConstraintVersion(parent.get("constraint_version"));
        fresh(authorization, parent);
        return parent;
    }
    private Map<String, Object> candidate(Map<String, Object> parent, UUID id) {
        var found = one("SELECT attempt_no FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND id = ?", parent.get("owner_id"), parent.get("id"), id.toString());
        if (found == null || number(found, "attempt_no") != 2 && number(found, "attempt_no") != 4) throw fenced();
        int number = Math.toIntExact(number(found, "attempt_no"));
        return attempt(parent, id, number == 2 ? "GENERATE" : "REPAIR", number);
    }
    private Map<String, Object> attempt(Map<String, Object> parent, UUID id, String kind, int number) {
        var row = one("SELECT * FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND id = ?", parent.get("owner_id"), parent.get("id"), id.toString());
        if (row == null || !kind.equals(row.get("call_kind")) || number(row, "attempt_no") != number || !"SUCCEEDED".equals(row.get("status"))
                || Boolean.TRUE.equals(row.get("archived_only")) || row.get("rejection_category") != null) throw fenced();
        requireAttemptIdentity(parent, row);
        var payload = new AdaptationAttemptModels.Terminal("SUCCEEDED", text(row, "output_text"), row.get("plan_json") == null ? null : jsonText(row.get("plan_json")),
                text(row, "finish_reason"), text(row, "provider_request_id"), nullableLong(row, "input_tokens"), nullableLong(row, "output_tokens"),
                row.get("http_status") == null ? null : Math.toIntExact(number(row, "http_status")), text(row, "error_code"), text(row, "diagnostic_sha256"));
        var checked = payloads.check(AdaptationAttemptModels.Kind.valueOf(kind), payload);
        if (!checked.sha256().equals(row.get("terminal_payload_sha256")) || !java.util.Objects.equals(checked.outputSha256(), row.get("output_sha256"))
                || !java.util.Objects.equals(checked.structuredSha256(), row.get("plan_sha256"))
                || checked.candidate() && (!Boolean.TRUE.equals(row.get("viewable_candidate")) || row.get("output_codepoint_count") == null
                    || checked.codepoints() != number(row, "output_codepoint_count"))) throw incomplete();
        return row;
    }
    private static void requireAttemptIdentity(Map<String, Object> parent, Map<String, Object> row) {
        if (!parent.get("task_instance_id").equals(row.get("task_instance_id")) || number(row, "fencing_token") > number(parent, "fencing_token")
                || number(row, "chapter_delete_epoch") != number(parent, "chapter_delete_epoch")
                || !Arrays.equals((byte[]) parent.get("provider_deployment_id"), (byte[]) row.get("provider_deployment_id"))
                || !Arrays.equals((byte[]) parent.get("model_id"), (byte[]) row.get("model_id"))
                || number(row, "credential_generation") != number(parent, "credential_generation")) throw fenced();
    }
    private AdaptationStoryModels.Constraints constraints(Map<String, Object> parent, Map<String, Object> stored) {
        requireConstraintVersion(parent.get("constraint_version"));
        // 历史 v1 与全文 v2 分别恢复，不能将另一版本的规则混入本次执行。
        if (!parent.get("constraint_version").equals(stored.get("constraint_version"))) throw incomplete();
        var plan = attempt(parent, UUID.fromString(text(stored, "plan_attempt_id")), "PLAN", 1);
        var value = new AdaptationStoryModels.Constraints(jsonText(stored.get("deterministic_json")), jsonText(stored.get("supplement_json")), jsonText(stored.get("merged_json")),
                text(stored, "deterministic_sha256"), text(stored, "supplement_sha256"), text(stored, "merged_sha256"));
        if (!engine.canonical(jsonText(plan.get("plan_json"))).equals(value.supplementJson())) throw incomplete();
        var restored = engine.restore(contexts.sealedForClaim(parent), value);
        if (!constraintVersion(restored.deterministicJson()).equals(parent.get("constraint_version"))) throw incomplete();
        return restored;
    }
    private Map<String, Object> requiredConstraints(Map<String, Object> parent) {
        var row = one("SELECT * FROM novel_chapter_adaptation_constraint_set WHERE owner_id = ? AND adaptation_id = ?", parent.get("owner_id"), parent.get("id"));
        if (row == null) throw incomplete(); return row;
    }
    private void verifyValidation(Map<String, Object> parent, Map<String, Object> stored, UUID criticId, AdaptationStoryModels.Evaluation evaluation, int round) {
        if (number(stored, "validation_round") != round || !criticId.toString().equals(stored.get("critic_attempt_id")) || !parent.get("constraint_version").equals(stored.get("deterministic_version"))
                || !AdaptationStoryEngine.CRITIC_VERSION.equals(stored.get("critic_version")) || !evaluation.reportSha256().equals(stored.get("report_sha256"))
                || !evaluation.reportJson().equals(jsonText(stored.get("report_json"))) || !evaluation.criticJson().equals(jsonText(stored.get("critic_json")))
                || !evaluation.deterministicJson().equals(jsonText(stored.get("deterministic_json"))) || !evaluation.outcome().equals(stored.get("outcome"))
                || !evaluation.contentPolicyOutcome().equals(stored.get("content_policy_outcome"))) throw conflict();
    }

    private static void requireConstraintVersion(Object version) {
        // 明确列举支持协议，未知版本仍拒绝，不能放宽为任意字符串。
        if (!AdaptationStoryEngine.VERSION.equals(version) && !AdaptationStoryEngine.REWRITE_VERSION.equals(version)) throw incomplete();
    }

    private String constraintVersion(String value) {
        try {
            String version = mapper.readTree(value).path("version").asText();
            requireConstraintVersion(version);
            return version;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw incomplete(); }
    }
    private void transition(Map<String, Object> parent, AdaptationStatus target, String stage, ChapterAdaptationStateMachine.Guards guards, String error) {
        ChapterAdaptationStateMachine.requireTransition(AdaptationStatus.valueOf(text(parent, "status")), target, guards);
        int changed = jdbc.update("""
                UPDATE novel_chapter_adaptation SET status = ?, current_stage = ?, last_error_code = ?, finished_at = ?, updated_at = ?, version = version + 1
                WHERE owner_id = ? AND id = ? AND version = ? AND current_execution_id = ? AND fencing_token = ? AND deletion_state = 'LIVE'
                    AND cancel_requested_at IS NULL AND dispatch_abort_error_code IS NULL
                """, target.name(), stage, error, target.terminal() ? stamp() : null, stamp(), parent.get("owner_id"), parent.get("id"), parent.get("version"),
                parent.get("current_execution_id"), parent.get("fencing_token"));
        if (changed != 1) throw fenced();
    }
    private ChapterAdaptationStateMachine.Guards guards(Map<String, Object> parent, boolean constraints, boolean candidate, boolean passed, boolean repairable, boolean selection) {
        return new ChapterAdaptationStateMachine.Guards("SEALED".equals(parent.get("context_status")), constraints, candidate, passed, repairable, selection,
                Math.toIntExact(number(parent, "repair_count")), 0);
    }
    private void nonePending(Map<String, Object> parent) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt WHERE owner_id = ? AND adaptation_id = ? AND status IN ('REGISTERED', 'SEND_STARTED')",
                Integer.class, parent.get("owner_id"), parent.get("id"));
        if (count == null || count != 0) throw fenced();
    }
    private AdaptationStoryModels.Validation validation(Map<String, Object> row, Map<String, Object> parent) {
        return new AdaptationStoryModels.Validation(UUID.fromString(text(row, "id")), UUID.fromString(text(row, "candidate_attempt_id")), UUID.fromString(text(row, "critic_attempt_id")),
                Math.toIntExact(number(row, "validation_round")), text(row, "outcome"), text(row, "content_policy_outcome"), text(row, "report_sha256"), view(parent));
    }
    private AdaptationStoryModels.Progress view(Map<String, Object> row) {
        return new AdaptationStoryModels.Progress(UUID.fromString(text(row, "id")), AdaptationStatus.valueOf(text(row, "status")), text(row, "current_stage"), number(row, "version"));
    }
    private Map<String, Object> reload(Map<String, Object> row) { return one("SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ?", row.get("owner_id"), row.get("id")); }
    private String jsonText(Object value) {
        if (value == null) throw incomplete();
        try {
            String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
            if (raw.length() > 262144) throw incomplete();
            var node = mapper.readTree(raw);
            return engine.canonical(node.isTextual() ? node.textValue() : raw);
        } catch (Exception exception) { throw incomplete(); }
    }
    private void fresh(WorkloadAuthorization authorization, Map<String, Object> parent) {
        if (!authorization.authorizedUntil().isAfter(clock.instant())) throw fenced();
        if (!instant(parent, "deadline_at").isAfter(clock.instant())) throw new ChapterAdaptationException(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
    }
    private <T> T atomic(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw fenced();
        try { return transaction.execute(ignored -> action.get()); }
        catch (DuplicateKeyException exception) { throw conflict(); }
        catch (DataAccessException exception) { throw new ChapterAdaptationException(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE); }
    }
    private Map<String, Object> one(String sql, Object... args) { var rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    private Timestamp stamp() { return Timestamp.from(clock.instant()); }
    private static String text(Map<String, Object> row, String key) { return (String) row.get(key); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long nullableLong(Map<String, Object> row, String key) { return row.get(key) == null ? null : number(row, key); }
    private static Instant instant(Map<String, Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static ChapterAdaptationException fenced() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private static ChapterAdaptationException incomplete() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE); }
    private static ChapterAdaptationException conflict() { return new ChapterAdaptationException(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT); }
}
