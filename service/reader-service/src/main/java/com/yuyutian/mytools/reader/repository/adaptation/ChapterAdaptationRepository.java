package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationLineage;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationViews;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 改编版本仓储：短事务串行化创建，结果通过独立采用关系读取，不覆盖已有正文。 */
@Repository
public class ChapterAdaptationRepository {
    private static final String VISIBLE_CANDIDATE = """
            t.viewable_candidate = TRUE AND t.call_kind IN ('GENERATE', 'REPAIR') AND t.status = 'SUCCEEDED'
            AND t.output_sha256 IS NOT NULL AND t.output_codepoint_count > 0
            AND (t.rejection_category IS NULL OR t.rejection_category = 'CONSTRAINTS')
            AND EXISTS (SELECT 1 FROM novel_chapter_adaptation_validation pv WHERE pv.owner_id = t.owner_id
                AND pv.adaptation_id = t.adaptation_id AND pv.candidate_attempt_id = t.id AND pv.content_policy_outcome = 'PASS')
            AND NOT EXISTS (SELECT 1 FROM novel_chapter_adaptation_validation bv WHERE bv.owner_id = t.owner_id
                AND bv.adaptation_id = t.adaptation_id AND bv.candidate_attempt_id = t.id AND bv.content_policy_outcome <> 'PASS')
            """;
    static final String SELECTED_RESULT = """
            SELECT t.id, t.output_sha256 FROM novel_chapter_adaptation_selection s
            JOIN novel_chapter_adaptation_attempt t ON t.id = s.candidate_attempt_id
                AND t.adaptation_id = s.adaptation_id AND t.owner_id = s.owner_id
            JOIN novel_chapter_adaptation_validation v ON v.id = s.validation_id
                AND v.adaptation_id = s.adaptation_id AND v.owner_id = s.owner_id AND v.candidate_attempt_id = t.id
            JOIN novel_chapter_adaptation_attempt critic ON critic.id = v.critic_attempt_id
                AND critic.adaptation_id = s.adaptation_id AND critic.owner_id = s.owner_id
            WHERE s.adaptation_id = ? AND s.owner_id = ? AND v.outcome = 'PASS'
                AND critic.call_kind = 'CRITIC' AND critic.status = 'SUCCEEDED' AND critic.archived_only = FALSE AND v.content_policy_outcome = 'PASS'
                AND t.rejection_category IS NULL AND t.archived_only = FALSE AND
            """ + VISIBLE_CANDIDATE;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ReaderAdaptationProperties properties;
    private final Clock clock;
    private final AdaptationStyleRepository styles;

    /** 注入事务依赖，不允许仓储在持锁期间调用模型或书源。 */
    @Autowired
    public ChapterAdaptationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                        ObjectMapper mapper, ReaderAdaptationProperties properties) {
        this(jdbc, manager, mapper, properties, Clock.systemUTC());
    }

    /** 测试可注入固定时钟，生产使用真实 UTC 时钟。 */
    public ChapterAdaptationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                        ObjectMapper mapper, ReaderAdaptationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.transaction = new TransactionTemplate(manager);
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
        this.styles = new AdaptationStyleRepository(jdbc, mapper, manager);
    }

    /** 先重放收据，再检查开关与限额；新操作原子写入收据、业务版本和谱系。 */
    public AdaptationViews.Accepted create(AdaptationCommand command) {
        requireRead();
        Objects.requireNonNull(command);
        return transaction.execute(ignored -> {
            lockOwner(command.ownerId());
            Map<String, Object> receipt = one("""
                    SELECT * FROM novel_chapter_adaptation_request_receipt WHERE owner_id = ? AND idempotency_key = ?
                    """, command.ownerId(), binary(command.idempotencyKey()));
            if (receipt != null) {
                // 收据先于单章活跃检查；成功后来源变化、配置停用仍可安全重放。
                return replay(command, receipt);
            }
            if (!properties.createEnabled()) {
                throw failure(ErrorCode.ADAPTATION_UNAVAILABLE);
            }
            Scope scope = lockScope(command.ownerId(), command.shelfBookId(), command.chapterId());
            requireCurrent(command, scope);
            Map<String, Object> deployment = deployment();
            // 新请求不要求额外授权，也不写入虚假的同意记录。
            long consentRevision = 0;
            requireCapacity(command.ownerId(), command.chapterId());
            UUID id = UUID.randomUUID();
            AdaptationLineage lineage = derive(command, id);
            long revision = number(scope.chapter(), "next_adaptation_revision");
            if (revision == Long.MAX_VALUE) {
                throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
            }
            Instant now = clock.instant();
            var accepted = new AdaptationViews.Accepted(id, command.chapterId(), revision, command.kind(),
                    AdaptationStatus.PENDING_DISPATCH, "CONTEXT_PENDING", 2000, now);
            UUID receiptId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_request_receipt (id, owner_id, idempotency_key, operation_kind,
                        shelf_book_id, chapter_id, trigger_adaptation_id,
                        request_fingerprint_sha256, canonicalization_version, adaptation_id, response_status,
                        response_snapshot_json, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 202, ?, ?, ?)
                    """, receiptId.toString(), command.ownerId(), binary(command.idempotencyKey()), command.kind().name(),
                    command.shelfBookId().toString(), command.chapterId().toString(), nullableId(command.triggerAdaptationId()),
                    command.fingerprint(), command.canonicalizationVersion(), id.toString(), json(accepted),
                    timestamp(now), timestamp(now.plusSeconds(7 * 86400L)));
            insertVersion(command, scope, deployment, accepted, receiptId, consentRevision);
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_lineage (child_adaptation_id, root_adaptation_id,
                        parent_adaptation_id, trigger_adaptation_id, owner_id, shelf_book_id, chapter_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id.toString(), lineage.rootAdaptationId().toString(), nullableId(lineage.parentAdaptationId()),
                    nullableId(lineage.triggerAdaptationId()), command.ownerId(), command.shelfBookId().toString(),
                    command.chapterId().toString());
            // 同一章节的单调版本号不因失败或将来的历史删除而回退。
            jdbc.update("UPDATE shelf_book_chapter SET next_adaptation_revision = ? WHERE id = ? AND owner_id = ?",
                    revision + 1, command.chapterId().toString(), command.ownerId());
            return accepted;
        });
    }

    /** 返回单个用户可见版本的轻量轮询状态。 */
    public AdaptationViews.Progress progress(long ownerId, UUID adaptationId) {
        requireRead();
        return transaction.execute(ignored -> progressOf(header(ownerId, adaptationId)));
    }

    /** 先按收据恢复派生范围，触发版本被删除后仍能按原始请求指纹返回 410 或冲突。 */
    public AdaptationViews.Target targetForDerivation(long ownerId, UUID adaptationId, AdaptationRequestKind kind, String key) {
        requireRead();
        AdaptationText.requireIdempotencyKey(key);
        Map<String, Object> receipt = one("SELECT * FROM novel_chapter_adaptation_request_receipt WHERE owner_id = ? AND idempotency_key = ?",
                ownerId, binary(key));
        if (receipt != null) {
            // 收据范围没有指向可擦除历史的外键，因此不依赖触发版本仍存在。
            if (!kind.name().equals(receipt.get("operation_kind"))
                    || !adaptationId.toString().equals(receipt.get("trigger_adaptation_id"))) {
                throw failure(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT);
            }
            return new AdaptationViews.Target(uuid(receipt, "shelf_book_id"), uuid(receipt, "chapter_id"));
        }
        Map<String, Object> row = header(ownerId, adaptationId);
        return new AdaptationViews.Target(uuid(row, "shelf_book_id"), uuid(row, "chapter_id"));
    }

    /** 按单调版本号倒序分页，不读取候选正文，旧章节历史仍可访问。 */
    public AdaptationViews.HistoryPage history(long ownerId, UUID shelfId, UUID chapterId, int limit, Long beforeRevision) {
        requireRead();
        if (limit < 1 || limit > 50 || (beforeRevision != null && beforeRevision <= 0)) {
            throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
        }
        return transaction.execute(ignored -> {
            requireOwnedChapter(ownerId, shelfId, chapterId);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT a.* FROM novel_chapter_adaptation a WHERE a.owner_id = ? AND a.shelf_book_id = ?
                        AND a.chapter_id = ? AND a.deletion_state = 'LIVE' AND a.revision_number < ?
                    ORDER BY a.revision_number DESC LIMIT ?
                    """, ownerId, shelfId.toString(), chapterId.toString(),
                    beforeRevision == null ? Long.MAX_VALUE : beforeRevision, limit + 1);
            boolean more = rows.size() > limit;
            List<AdaptationViews.HistoryItem> items = rows.stream().limit(limit).map(this::historyOf).toList();
            return new AdaptationViews.HistoryPage(items, more ? items.getLast().revisionNumber() : null);
        });
    }

    /** 单个详情只读取已通过校验的采用正文，来源缓存只能证明变化，不能证明实时未变。 */
    public AdaptationViews.Detail detail(long ownerId, UUID adaptationId) {
        requireRead();
        return transaction.execute(ignored -> {
            Map<String, Object> row = header(ownerId, adaptationId);
            AdaptationViews.HistoryItem version = historyOf(row);
            AdaptationViews.Output result = version.selectedAttemptId() == null ? null
                    : output(ownerId, adaptationId, version.selectedAttemptId(), true);
            List<AdaptationViews.Candidate> candidates = jdbc.queryForList("""
                    SELECT t.id, t.attempt_no, t.call_kind, t.status, t.rejection_category
                    FROM novel_chapter_adaptation_attempt t WHERE t.adaptation_id = ? AND t.owner_id = ? AND
                    """ + VISIBLE_CANDIDATE + " ORDER BY t.attempt_no", adaptationId.toString(), ownerId).stream()
                    .map(item -> candidate(item, version.selectedAttemptId(), ownerId, adaptationId)).toList();
            return new AdaptationViews.Detail(version, sourceRelation(row), clock.instant(), result, candidates);
        });
    }

    /** 仅按用户、业务版本及候选身份读取一个允许展示的正文。 */
    public AdaptationViews.Output attempt(long ownerId, UUID adaptationId, UUID attemptId) {
        requireRead();
        return transaction.execute(ignored -> {
            Map<String, Object> row = header(ownerId, adaptationId);
            UUID selected = selectedId(row);
            return output(ownerId, adaptationId, attemptId, attemptId.equals(selected));
        });
    }

    /** 显式取消与创建共用章节锁；存在派发歧义或未决调用时保留取消请求态。 */
    public AdaptationViews.Progress cancel(long ownerId, UUID adaptationId) {
        requireRead();
        return transaction.execute(ignored -> {
            Map<String, Object> known = header(ownerId, adaptationId);
            lockScope(ownerId, uuid(known, "shelf_book_id"), uuid(known, "chapter_id"));
            Map<String, Object> current = one("""
                    SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ? FOR UPDATE
                    """, ownerId, adaptationId.toString());
            requireLive(current);
            AdaptationStatus state = AdaptationStatus.valueOf(string(current, "status"));
            if (state.terminal() || state == AdaptationStatus.CANCEL_REQUESTED) {
                // 重复取消不能翻转终态，也不能在未决传输中提前宣布取消完成。
                return progressOf(current);
            }
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE novel_chapter_adaptation SET status = 'CANCEL_REQUESTED', current_stage = 'CANCELLING',
                        cancel_requested_at = ?, updated_at = ?, version = version + 1 WHERE owner_id = ? AND id = ?
                    """, timestamp(now), timestamp(now), ownerId, adaptationId.toString());
            boolean neverDispatched = state == AdaptationStatus.PENDING_DISPATCH && current.get("task_instance_id") == null
                    && current.get("dispatch_claim_owner") == null && number(current, "dispatch_attempt_count") == 0
                    && count("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt WHERE adaptation_id = ? AND owner_id = ?",
                    adaptationId.toString(), ownerId) == 0;
            if (neverDispatched) {
                // 只有完全没有派发和调用预留的本地任务可以立即收敛，其他情况交给恢复器。
                jdbc.update("""
                        UPDATE novel_chapter_adaptation SET status = 'CANCELLED', current_stage = 'CANCELLED',
                            finished_at = ?, updated_at = ?, version = version + 1 WHERE owner_id = ? AND id = ?
                        """, timestamp(now), timestamp(now), ownerId, adaptationId.toString());
            }
            return progressOf(header(ownerId, adaptationId));
        });
    }

    private void insertVersion(AdaptationCommand command, Scope scope, Map<String, Object> deployment,
                               AdaptationViews.Accepted accepted, UUID receiptId, long consentRevision) {
        Instant now = accepted.createdAt();
        var style = command.templateCode() == null ? null : styles.snapshot(command.templateCode(), command.templateVersion());
        String generationIntent = style == null ? null : AdaptationStyleRepository.generationIntent(style, command.normalizedIntent());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", accepted.adaptationId().toString()).addValue("owner", command.ownerId())
                .addValue("shelf", command.shelfBookId().toString()).addValue("chapter", command.chapterId().toString())
                .addValue("binding", scope.binding().get("id")).addValue("title", scope.chapter().get("chapter_title"))
                .addValue("kind", command.kind().name()).addValue("receipt", receiptId.toString())
                .addValue("fingerprint", command.fingerprint()).addValue("canonicalization", command.canonicalizationVersion())
                .addValue("intent", command.intent()).addValue("normalized", command.normalizedIntent())
                .addValue("intentSha", AdaptationText.sha256(command.normalizedIntent()))
                .addValue("bindingRevision", command.expectedBindingRevision()).addValue("catalogRevision", command.expectedCatalogRevision())
                .addValue("sourceSha", command.expectedSourceSha256()).addValue("disclosure", null)
                .addValue("revision", accepted.revisionNumber()).addValue("consentRevision", consentRevision).addValue("deleteEpoch", scope.chapter().get("adaptation_delete_epoch"))
                .addValue("baseKind", command.kind() == AdaptationRequestKind.OPTIMIZE ? "PARENT_RESULT" : "ORIGINAL")
                .addValue("deadline", timestamp(now.plusSeconds(840))).addValue("now", timestamp(now))
                .addValue("deployment", deployment.get("id")).addValue("provider", deployment.get("provider_code"))
                .addValue("model", deployment.get("model_id")).addValue("generation", deployment.get("credential_generation"))
                .addValue("prompt", style == null ? properties.promptVersion() : "novel-adaptation-v2")
                .addValue("constraint", style == null ? properties.constraintVersion() : "story-constraints-v2")
                .addValue("style", style == null ? null : json(style)).addValue("generationIntent", generationIntent)
                .addValue("generationSha", generationIntent == null ? null : AdaptationText.sha256(generationIntent));
        named.update("""
                INSERT INTO novel_chapter_adaptation (id, owner_id, shelf_book_id, chapter_id, content_binding_id,
                    chapter_title_snapshot, request_kind, request_receipt_id, request_fingerprint_sha256,
                    request_canonicalization_version, intent_text, normalized_intent_text, intent_sha256,
                    expected_binding_revision, expected_catalog_revision, expected_source_sha256, disclosure_version,
                    revision_number, chapter_delete_epoch, base_kind, context_status, status, current_stage, deadline_at,
                    next_dispatch_at, provider_deployment_id, provider_code, model_id, credential_generation,
                    prompt_version, constraint_version, created_at, updated_at, consent_revision,
                    style_template_snapshot_json, generation_intent_text, generation_intent_sha256, consent_policy)
                VALUES (:id, :owner, :shelf, :chapter, :binding, :title, :kind, :receipt, :fingerprint, :canonicalization,
                    :intent, :normalized, :intentSha, :bindingRevision, :catalogRevision, :sourceSha, :disclosure,
                    :revision, :deleteEpoch, :baseKind, 'BUILDING', 'PENDING_DISPATCH', 'CONTEXT_PENDING', :deadline,
                    :now, :deployment, :provider, :model, :generation, :prompt, :constraint, :now, :now, :consentRevision,
                    :style, :generationIntent, :generationSha, 'DIRECT')
                """, params);
    }

    private AdaptationViews.Accepted replay(AdaptationCommand command, Map<String, Object> receipt) {
        if (!command.fingerprint().equals(receipt.get("request_fingerprint_sha256"))
                || !command.canonicalizationVersion().equals(receipt.get("canonicalization_version"))) {
            throw failure(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT);
        }
        if (number(receipt, "response_status") == 410) {
            throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        }
        try {
            return mapper.readValue(jsonText(receipt.get("response_snapshot_json")), AdaptationViews.Accepted.class);
        } catch (JsonProcessingException exception) {
            // 数据库损坏不能通过重新创建掩盖，也不能把包含请求内容的解析异常向外透传。
            throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
    }

    private void requireCurrent(AdaptationCommand command, Scope scope) {
        Map<String, Object> binding = scope.binding();
        Map<String, Object> chapter = scope.chapter();
        if (!"ACTIVE".equals(binding.get("status")) || !Boolean.TRUE.equals(chapter.get("active"))
                || number(binding, "bound_shelf_version") != number(scope.shelf(), "version")
                || number(binding, "binding_revision") != command.expectedBindingRevision()
                || number(binding, "catalog_revision") != command.expectedCatalogRevision()
                || number(chapter, "binding_revision") != command.expectedBindingRevision()
                || number(chapter, "catalog_revision") != command.expectedCatalogRevision()
                || !binding.get("id").equals(chapter.get("content_binding_id"))) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        if (!"TEXT".equals(chapter.get("content_kind"))) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        if (command.expectedSourceSha256() != null && chapter.get("content_sha256") != null
                && !command.expectedSourceSha256().equals(chapter.get("content_sha256"))) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        // 此检查只拒绝已知不一致；后台 context seal 必须重新取可信正文并校验摘要。
    }

    private AdaptationLineage derive(AdaptationCommand command, UUID id) {
        if (command.kind() == AdaptationRequestKind.INITIAL) {
            return AdaptationLineage.initial(id);
        }
        Map<String, Object> trigger = one("""
                SELECT * FROM novel_chapter_adaptation WHERE owner_id = ? AND id = ? AND shelf_book_id = ? AND chapter_id = ?
                    AND deletion_state = 'LIVE' AND status = 'COMPLETED' AND context_status = 'SEALED'
                """, command.ownerId(), command.triggerAdaptationId().toString(), command.shelfBookId().toString(),
                command.chapterId().toString());
        if (trigger == null || selectedId(trigger) == null) {
            throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        if (command.expectedSourceSha256() == null
                || !command.expectedSourceSha256().equals(trigger.get("original_content_sha256"))
                || number(trigger, "expected_binding_revision") != command.expectedBindingRevision()
                || number(trigger, "expected_catalog_revision") != command.expectedCatalogRevision()) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        AdaptationLineage previous = lineage(trigger);
        Map<String, Object> root = header(command.ownerId(), previous.rootAdaptationId());
        if (!"SEALED".equals(root.get("context_status"))
                || !command.expectedSourceSha256().equals(root.get("original_content_sha256"))) {
            throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        return AdaptationLineage.derive(id, command.kind(), previous);
    }

    private void requireCapacity(long ownerId, UUID chapterId) {
        if (count("""
                SELECT COUNT(*) FROM novel_chapter_adaptation WHERE owner_id = ? AND chapter_id = ?
                    AND deletion_state = 'LIVE' AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, ownerId, chapterId.toString()) > 0 || count("""
                SELECT COUNT(*) FROM novel_chapter_adaptation_attempt t JOIN novel_chapter_adaptation a
                    ON a.id = t.adaptation_id AND a.owner_id = t.owner_id WHERE a.owner_id = ? AND a.chapter_id = ?
                    AND t.status IN ('REGISTERED', 'SEND_STARTED')
                """, ownerId, chapterId.toString()) > 0) {
            throw failure(ErrorCode.ADAPTATION_ALREADY_ACTIVE);
        }
        if (count("""
                SELECT COUNT(*) FROM novel_chapter_adaptation WHERE owner_id = ? AND deletion_state = 'LIVE'
                    AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, ownerId) >= properties.maximumActivePerOwner()) {
            throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
        }
    }

    private Map<String, Object> deployment() {
        Map<String, Object> row = one("SELECT * FROM novel_adaptation_provider_deployment WHERE id = ? AND enabled = TRUE",
                binary(properties.providerDeploymentId()));
        if (row == null) {
            throw failure(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
        }
        return row;
    }

    private void lockOwner(long ownerId) {
        try {
            jdbc.update("INSERT INTO reader_chapter_preparation_owner_guard (owner_id) VALUES (?)", ownerId);
        } catch (DuplicateKeyException exception) {
            // 与目录准备共用用户锁，重复行代表已有可用锁，不代表重复业务请求。
        }
        jdbc.queryForObject("SELECT owner_id FROM reader_chapter_preparation_owner_guard WHERE owner_id = ? FOR UPDATE",
                Long.class, ownerId);
    }

    private Scope lockScope(long ownerId, UUID shelfId, UUID chapterId) {
        Map<String, Object> shelf = one("SELECT * FROM shelf_book WHERE owner_id = ? AND id = ? AND deleted = FALSE FOR UPDATE",
                ownerId, shelfId.toString());
        if (shelf == null) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
        Map<String, Object> binding = one("SELECT * FROM shelf_book_content_binding WHERE owner_id = ? AND shelf_book_id = ? FOR UPDATE",
                ownerId, shelfId.toString());
        Map<String, Object> chapter = one("SELECT * FROM shelf_book_chapter WHERE owner_id = ? AND shelf_book_id = ? AND id = ? FOR UPDATE",
                ownerId, shelfId.toString(), chapterId.toString());
        if (binding == null || chapter == null) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
        return new Scope(shelf, binding, chapter);
    }

    private void requireOwnedChapter(long ownerId, UUID shelfId, UUID chapterId) {
        if (count("""
                SELECT COUNT(*) FROM shelf_book_chapter c JOIN shelf_book s ON s.id = c.shelf_book_id AND s.owner_id = c.owner_id
                WHERE c.owner_id = ? AND c.shelf_book_id = ? AND c.id = ? AND s.deleted = FALSE
                """, ownerId, shelfId.toString(), chapterId.toString()) != 1) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
    }

    private Map<String, Object> header(long ownerId, UUID id) {
        Map<String, Object> row = one("""
                SELECT a.* FROM novel_chapter_adaptation a JOIN shelf_book s ON s.id = a.shelf_book_id AND s.owner_id = a.owner_id
                WHERE a.owner_id = ? AND a.id = ? AND s.deleted = FALSE
                """, ownerId, id.toString());
        requireLive(row);
        return row;
    }

    private void requireLive(Map<String, Object> row) {
        if (row == null) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
        if (!"LIVE".equals(row.get("deletion_state"))) {
            throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        }
    }

    private AdaptationViews.Progress progressOf(Map<String, Object> row) {
        AdaptationStatus status = AdaptationStatus.valueOf(string(row, "status"));
        int candidates = count("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt t WHERE t.adaptation_id = ? AND t.owner_id = ? AND "
                + VISIBLE_CANDIDATE, row.get("id"), row.get("owner_id"));
        return new AdaptationViews.Progress(uuid(row, "id"), status, string(row, "current_stage"), number(row, "version"),
                candidates, string(row, "last_error_code"), status.terminal() ? 0 : 2000,
                instant(row, "created_at"), instant(row, "started_at"), instant(row, "finished_at"));
    }

    private AdaptationViews.HistoryItem historyOf(Map<String, Object> row) {
        var style = styles.parseSnapshot(row.get("style_template_snapshot_json"));
        return new AdaptationViews.HistoryItem(uuid(row, "id"), uuid(row, "shelf_book_id"), uuid(row, "chapter_id"),
                string(row, "chapter_title_snapshot"), number(row, "revision_number"),
                AdaptationRequestKind.valueOf(string(row, "request_kind")), lineage(row), string(row, "intent_text"),
                AdaptationStatus.valueOf(string(row, "status")), string(row, "current_stage"), selectedId(row),
                (int) number(row, "attempt_count"), new String((byte[]) row.get("model_id"), StandardCharsets.UTF_8),
                string(row, "last_error_code"), instant(row, "created_at"), instant(row, "finished_at"),
                style == null ? null : style.template());
    }

    private AdaptationLineage lineage(Map<String, Object> row) {
        Map<String, Object> relation = one("SELECT * FROM novel_chapter_adaptation_lineage WHERE child_adaptation_id = ? AND owner_id = ?",
                row.get("id"), row.get("owner_id"));
        if (relation == null) {
            throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        return new AdaptationLineage(uuid(relation, "child_adaptation_id"), uuid(relation, "root_adaptation_id"),
                uuid(relation, "parent_adaptation_id"), uuid(relation, "trigger_adaptation_id"));
    }

    private UUID selectedId(Map<String, Object> row) {
        if (!"COMPLETED".equals(row.get("status")) || !"SEALED".equals(row.get("context_status"))) {
            return null;
        }
        Map<String, Object> selected = one(SELECTED_RESULT, row.get("id"), row.get("owner_id"));
        return selected == null ? null : uuid(selected, "id");
    }

    private AdaptationViews.Output output(long ownerId, UUID adaptationId, UUID attemptId, boolean selected) {
        Map<String, Object> row = one("""
                SELECT t.id, t.output_text, t.output_sha256, t.output_codepoint_count, t.rejection_category
                FROM novel_chapter_adaptation_attempt t WHERE t.owner_id = ? AND t.adaptation_id = ? AND t.id = ? AND
                """ + VISIBLE_CANDIDATE, ownerId, adaptationId.toString(), attemptId.toString());
        if (row == null) {
            throw failure(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND);
        }
        String text = string(row, "output_text");
        int length = AdaptationText.requireText(text, 1, 120000, ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        if (!AdaptationText.sha256(text).equals(row.get("output_sha256")) || length != number(row, "output_codepoint_count")) {
            throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
        return new AdaptationViews.Output(attemptId, text, string(row, "output_sha256"),
                selected ? "SELECTED" : constraintsRejected(row, ownerId, adaptationId) ? "REJECTED_BY_CONSTRAINTS" : "NOT_SELECTED");
    }

    private AdaptationViews.Candidate candidate(Map<String, Object> row, UUID selectedId, long ownerId, UUID adaptationId) {
        UUID id = uuid(row, "id");
        boolean selected = id.equals(selectedId);
        return new AdaptationViews.Candidate(id, (int) number(row, "attempt_no"), string(row, "call_kind"), string(row, "status"),
                selected ? "SELECTED" : constraintsRejected(row, ownerId, adaptationId) ? "REJECTED_BY_CONSTRAINTS" : "NOT_SELECTED",
                selected, true);
    }

    private boolean constraintsRejected(Map<String, Object> row, long ownerId, UUID adaptationId) {
        // 候选终态不改写，后续校验失败通过不可变 validation 投影为警告。
        return "CONSTRAINTS".equals(row.get("rejection_category")) || count("""
                SELECT COUNT(*) FROM novel_chapter_adaptation_validation WHERE owner_id = ? AND adaptation_id = ?
                    AND candidate_attempt_id = ? AND content_policy_outcome = 'PASS' AND outcome <> 'PASS'
                """, ownerId, adaptationId.toString(), row.get("id")) > 0;
    }

    private String sourceRelation(Map<String, Object> row) {
        Map<String, Object> current = one("""
                SELECT c.active, c.binding_revision, c.catalog_revision, c.content_sha256, b.status,
                    b.bound_shelf_version, s.version FROM shelf_book_chapter c
                JOIN shelf_book_content_binding b ON b.id = c.content_binding_id AND b.owner_id = c.owner_id
                JOIN shelf_book s ON s.id = c.shelf_book_id AND s.owner_id = c.owner_id
                WHERE c.id = ? AND c.owner_id = ?
                """, row.get("chapter_id"), row.get("owner_id"));
        if (current == null || !Boolean.TRUE.equals(current.get("active"))
                || number(current, "binding_revision") != number(row, "expected_binding_revision")
                || number(current, "catalog_revision") != number(row, "expected_catalog_revision")
                || number(current, "bound_shelf_version") != number(current, "version")
                || (current.get("content_sha256") != null && row.get("original_content_sha256") != null
                && !current.get("content_sha256").equals(row.get("original_content_sha256")))) {
            return "STALE";
        }
        // 相同缓存摘要不代表刚刚重取过书源，后续服务层完成实时检查后才能投影 CURRENT。
        return "UNKNOWN";
    }

    private void requireRead() {
        if (!properties.readEnabled()) {
            throw failure(ErrorCode.ADAPTATION_UNAVAILABLE);
        }
    }

    private Map<String, Object> one(String sql, Object... arguments) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
        }
    }

    private String jsonText(Object value) throws JsonProcessingException {
        String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        // H2 的 JSON 参数可能保存为 JSON 字符串，MySQL 则返回直接对象；只解包一次。
        var node = mapper.readTree(text);
        return node.isTextual() ? node.textValue() : text;
    }

    private static String string(Map<String, Object> row, String column) {
        return (String) row.get(column);
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static UUID uuid(Map<String, Object> row, String column) {
        String value = string(row, column);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        Timestamp value = (Timestamp) row.get(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static byte[] binary(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String nullableId(UUID id) {
        return id == null ? null : id.toString();
    }

    private static ChapterAdaptationException failure(ErrorCode code) {
        return new ChapterAdaptationException(code);
    }

    private record Scope(Map<String, Object> shelf, Map<String, Object> binding, Map<String, Object> chapter) {
        /** 私有数据库快照也不能通过异常诊断泄漏元数据或定位符。 */
        @Override
        public String toString() {
            return "Scope[redacted]";
        }
    }
}
