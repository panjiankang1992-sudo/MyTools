package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextFragment;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextIdentity;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextPlan;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionFence;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationLineage;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 冻结上下文的唯一写入路径；调用者须先完成执行授权，此仓储再验证数据库 fence。 */
@Repository
public class AdaptationContextRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** 注入短事务依赖，不在持锁期间执行网络读取。 */
    @Autowired
    public AdaptationContextRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this(jdbc, mapper, manager, Clock.systemUTC());
    }

    /** 为竞争、取消和授权期限测试提供可控时钟。 */
    public AdaptationContextRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        // 首次无锁身份查询不能使后续已加锁的目录检查仍读取 MySQL 旧快照。
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    /** 解析当前执行的目标与真实邻章；已封存时只返回完整快照，不重取外部来源。 */
    public AdaptationContextPlan plan(AdaptationExecutionFence execution) {
        requireIndependentTransaction();
        return transaction.execute(ignored -> {
            Map<String, Object> row = lockExecution(execution);
            if ("SEALED".equals(row.get("context_status"))) {
                AdaptationContextSnapshot snapshot = stored(row);
                return new AdaptationContextPlan(snapshot.identity(), snapshot.kind(), null, null, null, null, snapshot);
            }
            requireFreezing(row);
            return currentPlan(row);
        });
    }

    /** 所有片段及封存头在同一事务写入，重放只能返回相同快照，不能修补半份封存。 */
    public AdaptationContextSnapshot seal(AdaptationExecutionFence execution, AdaptationContextPlan planned,
                                           List<ShelfChapterModels.Content> contents) {
        requireIndependentTransaction();
        if (planned == null || contents == null || contents.size() > 3) {
            throw incomplete();
        }
        return transaction.execute(ignored -> {
            Map<String, Object> row = lockExecution(execution);
            AdaptationContextSnapshot proposed = assemble(planned, contents);
            if ("SEALED".equals(row.get("context_status"))) {
                AdaptationContextSnapshot previous = stored(row);
                if (!previous.manifestSha256().equals(proposed.manifestSha256())) {
                    throw incomplete();
                }
                // 同一执行的重复提交无需外部来源仍可用，但只允许完全相同的封存摘要。
                return previous;
            }
            requireFreezing(row);
            AdaptationContextPlan current = currentPlan(row);
            if (!current.identity().equals(planned.identity()) || current.kind() != planned.kind()
                    || !Objects.equals(current.catalogMetadata(), planned.catalogMetadata())
                    || !Objects.equals(current.expectedSourceSha256(), planned.expectedSourceSha256())) {
                throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
            }
            // 重新读取父结果与根快照后再次组装，不能信任取文前计划中的可替换底稿。
            AdaptationContextSnapshot validated = assemble(current, contents);
            if (!validated.manifestSha256().equals(proposed.manifestSha256())) {
                throw incomplete();
            }
            verifyContentTokens(current, contents);
            if (!jdbc.queryForList("SELECT id FROM novel_chapter_adaptation_context WHERE adaptation_id = ? LIMIT 1",
                    row.get("id")).isEmpty()) {
                // BUILDING 中残留任何正文都是不完整状态，拒绝覆盖，交给人工或恢复审计。
                throw incomplete();
            }
            Instant now = clock.instant();
            for (AdaptationContextFragment fragment : validated.fragments()) {
                jdbc.update("""
                        INSERT INTO novel_chapter_adaptation_context (id, adaptation_id, owner_id, shelf_book_id, chapter_id,
                            context_role, sequence_no, source_chapter_id, content_text, content_sha256, codepoint_count, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), row.get("id"), row.get("owner_id"), row.get("shelf_book_id"), row.get("chapter_id"),
                        fragment.role().name(), id(fragment.sourceChapterId()), fragment.text(), fragment.contentSha256(),
                        fragment.codepointCount(), timestamp(now));
            }
            int changed = jdbc.update("""
                    UPDATE novel_chapter_adaptation SET context_status = 'SEALED', context_role_count = ?,
                        context_manifest_version = ?, context_manifest_sha256 = ?, context_identity_json = ?, context_sealed_at = ?,
                        original_content_sha256 = ?, base_content_sha256 = ?, current_stage = 'CONTEXT_FROZEN',
                        updated_at = ?, version = version + 1
                    WHERE id = ? AND owner_id = ? AND current_execution_id = ? AND fencing_token = ?
                        AND chapter_delete_epoch = ? AND status = 'CONTEXT_FREEZING' AND context_status = 'BUILDING'
                        AND deletion_state = 'LIVE' AND deadline_at > ?
                    """, validated.fragments().size(), AdaptationContextSnapshot.MANIFEST_VERSION, validated.manifestSha256(),
                    json(validated.identity()), timestamp(now), validated.originalContentSha256(), validated.baseContentSha256(),
                    timestamp(now), row.get("id"), row.get("owner_id"), execution.executionId().toString(), execution.fencingToken(),
                    execution.chapterDeleteEpoch(), timestamp(clock.instant()));
            if (changed != 1 || !execution.authorizedUntil().isAfter(clock.instant())) {
                throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
            }
            // 片段插入后 CAS 失败会回滚全部片段，下一次执行不能看见半份正文。
            return validated;
        });
    }

    /** 从已验证执行范围读取完整封存，不把 BUILDING 中的暂存内容向外暴露。 */
    public AdaptationContextSnapshot frozen(AdaptationExecutionFence execution) {
        requireIndependentTransaction();
        return transaction.execute(ignored -> stored(lockExecution(execution)));
    }

    // 只供同包领取仓储在已经持有统一范围锁的 READ_COMMITTED 事务中验证封存，避免领取后才发现坏快照。
    AdaptationContextSnapshot sealedForClaim(Map<String, Object> lockedRow) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
        return stored(lockedRow);
    }

    // 来源核验仓储已经持有相同顺序的范围锁；此路径不改变任何历史或执行状态。
    AdaptationContextPlan sourceVerificationPlan(Map<String, Object> lockedRow) {
        AdaptationContextSnapshot frozen = sealedForClaim(lockedRow);
        AdaptationContextPlan current = currentPlan(lockedRow);
        if (!frozen.identity().equals(current.identity())
                || !fragment(frozen, AdaptationContextRole.CATALOG_METADATA).text().equals(current.catalogMetadata())) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        return current;
    }

    // 与后台封存复用完整正文、邻章截取、真实边界及根谱系规则，而不是另写弱化版比较器。
    String verifySourceContents(Map<String, Object> lockedRow, AdaptationContextPlan planned,
                                List<ShelfChapterModels.Content> contents) {
        AdaptationContextPlan current = sourceVerificationPlan(lockedRow);
        if (!current.identity().equals(planned.identity()) || !current.catalogMetadata().equals(planned.catalogMetadata())) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        AdaptationContextSnapshot fresh = assemble(current, contents);
        AdaptationContextSnapshot frozen = stored(lockedRow);
        if (!fresh.manifestSha256().equals(frozen.manifestSha256())) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        verifyContentTokens(current, contents);
        return frozen.manifestSha256();
    }

    /** 历史比较只提取本人版本的冻结原章，不把邻章或优化底稿对外暴露。 */
    public AdaptationContextFragment original(long ownerId, UUID adaptationId) {
        return transaction.execute(ignored -> {
            Map<String, Object> row = one("""
                    SELECT a.* FROM novel_chapter_adaptation a JOIN shelf_book s ON s.id = a.shelf_book_id AND s.owner_id = a.owner_id
                    WHERE a.id = ? AND a.owner_id = ? AND s.deleted = FALSE
                    """, adaptationId.toString(), ownerId);
            requireLive(row);
            return fragment(stored(row), AdaptationContextRole.TARGET_ORIGINAL);
        });
    }

    private Map<String, Object> lockExecution(AdaptationExecutionFence execution) {
        if (execution == null || !execution.authorizedUntil().isAfter(clock.instant())) {
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
        Map<String, Object> known = one("SELECT * FROM novel_chapter_adaptation WHERE id = ?", execution.adaptationId().toString());
        requireLive(known);
        // 统一采用 shelf → binding → chapter → adaptation 锁顺序，与创建、取消及来源发布相容。
        Map<String, Object> shelf = one("SELECT id FROM shelf_book WHERE id = ? AND owner_id = ? AND deleted = FALSE FOR UPDATE",
                known.get("shelf_book_id"), known.get("owner_id"));
        if (shelf == null) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
        one("SELECT id FROM shelf_book_content_binding WHERE id = ? AND owner_id = ? FOR UPDATE",
                known.get("content_binding_id"), known.get("owner_id"));
        Map<String, Object> chapter = one("SELECT adaptation_delete_epoch FROM shelf_book_chapter WHERE id = ? AND owner_id = ? FOR UPDATE",
                known.get("chapter_id"), known.get("owner_id"));
        Map<String, Object> row = one("SELECT * FROM novel_chapter_adaptation WHERE id = ? FOR UPDATE", execution.adaptationId().toString());
        requireLive(row);
        if (chapter == null || number(chapter, "adaptation_delete_epoch") != execution.chapterDeleteEpoch()
                || number(row, "chapter_delete_epoch") != execution.chapterDeleteEpoch()) {
            throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        }
        if (row.get("task_instance_id") == null) {
            throw failure(ErrorCode.ADAPTATION_TASK_BIND_PENDING);
        }
        AdaptationStatus state = AdaptationStatus.valueOf(string(row, "status"));
        if (!execution.taskInstanceId().toString().equals(row.get("task_instance_id"))
                || !execution.executionId().toString().equals(row.get("current_execution_id"))
                || row.get("fencing_token") == null || number(row, "fencing_token") != execution.fencingToken()
                || state.terminal() || state == AdaptationStatus.CANCEL_REQUESTED || row.get("dispatch_abort_error_code") != null
                || !execution.authorizedUntil().isAfter(clock.instant())) {
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
        if (!instant(row, "deadline_at").isAfter(clock.instant())) {
            throw failure(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED);
        }
        return row;
    }

    private AdaptationContextPlan currentPlan(Map<String, Object> row) {
        Map<String, Object> binding = one("""
                SELECT b.*, s.version AS shelf_version FROM shelf_book_content_binding b
                JOIN shelf_book s ON s.id = b.shelf_book_id AND s.owner_id = b.owner_id WHERE b.id = ? AND b.owner_id = ?
                """, row.get("content_binding_id"), row.get("owner_id"));
        if (binding == null || !"ACTIVE".equals(binding.get("status"))
                || number(binding, "bound_shelf_version") != number(binding, "shelf_version")
                || number(binding, "binding_revision") != number(row, "expected_binding_revision")
                || number(binding, "catalog_revision") != number(row, "expected_catalog_revision")) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        Map<String, Object> catalog = one("""
                SELECT COUNT(*) AS chapter_count, COUNT(DISTINCT chapter_index) AS index_count,
                    MIN(chapter_index) AS first_index, MAX(chapter_index) AS last_index,
                    SUM(CASE WHEN content_binding_id = ? AND binding_revision = ? AND catalog_revision = ? THEN 0 ELSE 1 END) AS mismatches
                FROM shelf_book_chapter WHERE owner_id = ? AND shelf_book_id = ? AND active = TRUE
                """, row.get("content_binding_id"), row.get("expected_binding_revision"), row.get("expected_catalog_revision"),
                row.get("owner_id"), row.get("shelf_book_id"));
        long count = number(catalog, "chapter_count");
        if (count < 1 || count > 50000 || count != number(catalog, "index_count") || number(catalog, "first_index") != 0
                || number(catalog, "last_index") != count - 1 || number(catalog, "mismatches") != 0) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        Map<String, Object> target = one("SELECT * FROM shelf_book_chapter WHERE id = ? AND owner_id = ? AND active = TRUE",
                row.get("chapter_id"), row.get("owner_id"));
        if (target == null) {
            throw failure(ErrorCode.CHAPTER_CATALOG_STALE);
        }
        int index = (int) number(target, "chapter_index");
        List<Map<String, Object>> neighbors = jdbc.queryForList("""
                SELECT * FROM shelf_book_chapter WHERE owner_id = ? AND shelf_book_id = ? AND active = TRUE
                    AND chapter_index BETWEEN ? AND ? ORDER BY chapter_index FOR UPDATE
                """, row.get("owner_id"), row.get("shelf_book_id"), Math.max(0, index - 1), Math.min(count - 1, index + 1));
        if (neighbors.stream().anyMatch(item -> !"TEXT".equals(item.get("content_kind")))) {
            throw failure(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE);
        }
        UUID previous = index == 0 ? null : uuid(neighbors.getFirst(), "id");
        UUID next = index == count - 1 ? null : uuid(neighbors.getLast(), "id");
        var identity = new AdaptationContextIdentity(uuid(row, "id"), number(row, "owner_id"), uuid(row, "shelf_book_id"),
                uuid(row, "content_binding_id"), number(row, "expected_binding_revision"), number(row, "expected_catalog_revision"),
                uuid(row, "chapter_id"), index, (int) count, previous, next);
        String metadata = metadata(binding, identity, neighbors);
        AdaptationRequestKind kind = AdaptationRequestKind.valueOf(string(row, "request_kind"));
        AdaptationContextSnapshot root = null;
        String parent = null;
        if (kind != AdaptationRequestKind.INITIAL) {
            Map<String, Object> relation = one("SELECT * FROM novel_chapter_adaptation_lineage WHERE child_adaptation_id = ? AND owner_id = ?",
                    row.get("id"), row.get("owner_id"));
            if (relation == null) {
                throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
            }
            var lineage = new AdaptationLineage(uuid(relation, "child_adaptation_id"), uuid(relation, "root_adaptation_id"),
                    uuid(relation, "parent_adaptation_id"), uuid(relation, "trigger_adaptation_id"));
            root = stored(one("SELECT * FROM novel_chapter_adaptation WHERE id = ? AND owner_id = ?",
                    lineage.rootAdaptationId().toString(), row.get("owner_id")));
            if (root.kind() != AdaptationRequestKind.INITIAL || !sameSource(identity, root.identity())
                    || !root.originalContentSha256().equals(row.get("expected_source_sha256"))) {
                throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
            }
            if (kind == AdaptationRequestKind.OPTIMIZE) {
                if (lineage.parentAdaptationId() == null) {
                    throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
                }
                parent = parentOutput(row, lineage.parentAdaptationId());
            }
        }
        return new AdaptationContextPlan(identity, kind, metadata, string(row, "expected_source_sha256"), root, parent, null);
    }

    private AdaptationContextSnapshot assemble(AdaptationContextPlan plan, List<ShelfChapterModels.Content> contents) {
        if (plan.identity() == null || plan.kind() == null || plan.catalogMetadata() == null || plan.alreadySealed() != null) {
            throw incomplete();
        }
        var identity = plan.identity();
        Map<UUID, ShelfChapterModels.Content> byId = new HashMap<>();
        for (ShelfChapterModels.Content content : contents) {
            if (content == null || content.chapterId() == null || byId.putIfAbsent(content.chapterId(), content) != null
                    || content.bindingRevision() != identity.bindingRevision() || content.catalogRevision() != identity.catalogRevision()) {
                throw incomplete();
            }
            requireContent(content.text(), content.sha256(), content.codepointCount());
        }
        int expected = 1 + (identity.previousChapterId() == null ? 0 : 1) + (identity.nextChapterId() == null ? 0 : 1);
        if (byId.size() != expected || !byId.containsKey(identity.targetChapterId())
                || (identity.previousChapterId() != null && !byId.containsKey(identity.previousChapterId()))
                || (identity.nextChapterId() != null && !byId.containsKey(identity.nextChapterId()))) {
            throw incomplete();
        }
        List<AdaptationContextFragment> fragments = new ArrayList<>();
        fragments.add(new AdaptationContextFragment(AdaptationContextRole.TARGET_ORIGINAL, identity.targetChapterId(),
                byId.get(identity.targetChapterId()).text()));
        fragments.add(new AdaptationContextFragment(AdaptationContextRole.CATALOG_METADATA, null, plan.catalogMetadata()));
        fragments.add(identity.previousChapterId() == null
                ? new AdaptationContextFragment(AdaptationContextRole.BOOK_START_MARKER, null, "BOOK_START")
                : new AdaptationContextFragment(AdaptationContextRole.PREVIOUS_TAIL, identity.previousChapterId(),
                excerpt(byId.get(identity.previousChapterId()).text(), true)));
        fragments.add(identity.nextChapterId() == null
                ? new AdaptationContextFragment(AdaptationContextRole.BOOK_END_MARKER, null, "BOOK_END")
                : new AdaptationContextFragment(AdaptationContextRole.NEXT_HEAD, identity.nextChapterId(),
                excerpt(byId.get(identity.nextChapterId()).text(), false)));
        if (plan.expectedSourceSha256() != null && !plan.expectedSourceSha256().equals(byId.get(identity.targetChapterId()).sha256())) {
            throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
        }
        if (plan.rootSnapshot() != null) {
            for (AdaptationContextFragment fresh : fragments) {
                if (!fresh.equals(fragment(plan.rootSnapshot(), fresh.role()))) {
                    // 派生沿用根约束，当前邻章摘录变化时拒绝继续派生，不能悄然混合两份上下文。
                    throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
                }
            }
        } else if (plan.kind() != AdaptationRequestKind.INITIAL) {
            throw incomplete();
        }
        if (plan.kind() == AdaptationRequestKind.OPTIMIZE) {
            fragments.add(new AdaptationContextFragment(AdaptationContextRole.BASE_INPUT, identity.targetChapterId(), plan.parentOutput()));
        } else if (plan.parentOutput() != null) {
            throw incomplete();
        }
        return new AdaptationContextSnapshot(identity, plan.kind(), fragments);
    }

    private void verifyContentTokens(AdaptationContextPlan plan, List<ShelfChapterModels.Content> contents) {
        for (ShelfChapterModels.Content content : contents) {
            Map<String, Object> current = one("""
                    SELECT content_sha256 FROM shelf_book_chapter WHERE id = ? AND owner_id = ? AND shelf_book_id = ?
                        AND content_binding_id = ? AND binding_revision = ? AND catalog_revision = ? AND active = TRUE
                    """, content.chapterId().toString(), plan.identity().ownerId(), plan.identity().shelfBookId().toString(),
                    plan.identity().bindingId().toString(), content.bindingRevision(), content.catalogRevision());
            if (current == null || !content.sha256().equals(current.get("content_sha256"))) {
                throw failure(ErrorCode.CHAPTER_SOURCE_CHANGED);
            }
        }
    }

    private AdaptationContextSnapshot stored(Map<String, Object> row) {
        requireLive(row);
        if (!"SEALED".equals(row.get("context_status"))
                || !AdaptationContextSnapshot.MANIFEST_VERSION.equals(row.get("context_manifest_version"))) {
            throw incomplete();
        }
        try {
            AdaptationContextIdentity identity = mapper.readValue(jsonText(row.get("context_identity_json")), AdaptationContextIdentity.class);
            if (!identity.adaptationId().equals(uuid(row, "id")) || identity.ownerId() != number(row, "owner_id")
                    || !identity.shelfBookId().equals(uuid(row, "shelf_book_id")) || !identity.bindingId().equals(uuid(row, "content_binding_id"))
                    || !identity.targetChapterId().equals(uuid(row, "chapter_id"))
                    || identity.bindingRevision() != number(row, "expected_binding_revision")
                    || identity.catalogRevision() != number(row, "expected_catalog_revision")) {
                throw incomplete();
            }
            List<AdaptationContextFragment> fragments = new ArrayList<>();
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT * FROM novel_chapter_adaptation_context WHERE adaptation_id = ? AND owner_id = ?
                    ORDER BY context_role LIMIT 6
                    """, row.get("id"), row.get("owner_id"));
            for (Map<String, Object> item : rows) {
                var fragment = new AdaptationContextFragment(AdaptationContextRole.valueOf(string(item, "context_role")),
                        uuid(item, "source_chapter_id"), string(item, "content_text"));
                if (!fragment.contentSha256().equals(item.get("content_sha256"))
                        || fragment.codepointCount() != number(item, "codepoint_count")) {
                    throw incomplete();
                }
                fragments.add(fragment);
            }
            var snapshot = new AdaptationContextSnapshot(identity, AdaptationRequestKind.valueOf(string(row, "request_kind")), fragments);
            if (snapshot.fragments().size() != number(row, "context_role_count")
                    || !snapshot.manifestSha256().equals(row.get("context_manifest_sha256"))
                    || !snapshot.originalContentSha256().equals(row.get("original_content_sha256"))
                    || !snapshot.baseContentSha256().equals(row.get("base_content_sha256"))) {
                throw incomplete();
            }
            return snapshot;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw incomplete();
        }
    }

    private String parentOutput(Map<String, Object> child, UUID parentId) {
        Map<String, Object> result = one("""
                SELECT t.output_text, t.output_sha256, t.output_codepoint_count FROM novel_chapter_adaptation a
                JOIN novel_chapter_adaptation_selection s ON s.adaptation_id = a.id AND s.owner_id = a.owner_id
                JOIN novel_chapter_adaptation_attempt t ON t.id = s.candidate_attempt_id AND t.adaptation_id = a.id AND t.owner_id = a.owner_id
                JOIN novel_chapter_adaptation_validation v ON v.id = s.validation_id AND v.adaptation_id = a.id
                    AND v.owner_id = a.owner_id AND v.candidate_attempt_id = t.id
                JOIN novel_chapter_adaptation_attempt critic ON critic.id = v.critic_attempt_id
                    AND critic.adaptation_id = a.id AND critic.owner_id = a.owner_id
                WHERE a.id = ? AND a.owner_id = ? AND a.shelf_book_id = ? AND a.chapter_id = ?
                    AND a.deletion_state = 'LIVE' AND a.status = 'COMPLETED' AND a.context_status = 'SEALED'
                    AND t.call_kind IN ('GENERATE', 'REPAIR') AND t.status = 'SUCCEEDED' AND t.viewable_candidate = TRUE
                    AND t.output_text IS NOT NULL AND t.output_sha256 IS NOT NULL AND t.output_codepoint_count BETWEEN 1 AND 120000
                    AND t.archived_only = FALSE AND t.rejection_category IS NULL AND v.outcome = 'PASS'
                    AND critic.call_kind = 'CRITIC' AND critic.status = 'SUCCEEDED'
                """, parentId.toString(), child.get("owner_id"), child.get("shelf_book_id"), child.get("chapter_id"));
        if (result == null) {
            throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
        }
        requireContent(string(result, "output_text"), string(result, "output_sha256"), number(result, "output_codepoint_count"));
        return string(result, "output_text");
    }

    private String metadata(Map<String, Object> binding, AdaptationContextIdentity identity, List<Map<String, Object>> neighbors) {
        ObjectNode object = mapper.createObjectNode();
        object.put("version", "adaptation-catalog-window-v1");
        object.put("catalogSha256", string(binding, "catalog_sha256"));
        object.put("chapterCount", identity.catalogChapterCount());
        object.put("targetChapterId", identity.targetChapterId().toString());
        var window = object.putArray("chapters");
        for (Map<String, Object> chapter : neighbors) {
            window.addObject().put("chapterId", string(chapter, "id")).put("index", number(chapter, "chapter_index"))
                    .put("title", string(chapter, "chapter_title"));
        }
        return json(object);
    }

    private static boolean sameSource(AdaptationContextIdentity first, AdaptationContextIdentity second) {
        return first.ownerId() == second.ownerId() && first.shelfBookId().equals(second.shelfBookId())
                && first.bindingId().equals(second.bindingId()) && first.bindingRevision() == second.bindingRevision()
                && first.catalogRevision() == second.catalogRevision() && first.targetChapterId().equals(second.targetChapterId())
                && first.targetIndex() == second.targetIndex() && first.catalogChapterCount() == second.catalogChapterCount()
                && Objects.equals(first.previousChapterId(), second.previousChapterId()) && Objects.equals(first.nextChapterId(), second.nextChapterId());
    }

    private static AdaptationContextFragment fragment(AdaptationContextSnapshot snapshot, AdaptationContextRole role) {
        return snapshot.fragments().stream().filter(item -> item.role() == role).findFirst().orElseThrow(AdaptationContextRepository::incomplete);
    }

    private static String excerpt(String text, boolean tail) {
        int length = text.codePointCount(0, text.length());
        if (length <= 4000) {
            return text;
        }
        // 按码点截取邻章，补充平面字符不能被切成孤立代理项；目标正文永不截断。
        return tail ? text.substring(text.offsetByCodePoints(0, length - 4000)) : text.substring(0, text.offsetByCodePoints(0, 4000));
    }

    private static void requireContent(String text, String sha256, long count) {
        if (AdaptationText.requireText(text, 1, 120000, ErrorCode.ADAPTATION_CONTENT_TOO_LARGE) != count
                || !AdaptationText.sha256(text).equals(sha256)) {
            throw incomplete();
        }
    }

    private static void requireFreezing(Map<String, Object> row) {
        if (!"CONTEXT_FREEZING".equals(row.get("status"))) {
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
    }

    private static void requireIndependentTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 不允许加入调用方未知隔离级别的事务，避免读到加锁前的旧目录。
            throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        }
    }

    private static void requireLive(Map<String, Object> row) {
        if (row == null) {
            throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        }
        if (!"LIVE".equals(row.get("deletion_state"))) {
            throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        }
    }

    private Map<String, Object> one(String sql, Object... parameters) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw incomplete();
        }
    }

    private String jsonText(Object value) throws JsonProcessingException {
        String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        var node = mapper.readTree(text);
        return node.isTextual() ? node.textValue() : text;
    }

    private static String string(Map<String, Object> row, String column) {
        return (String) row.get(column);
    }

    private static UUID uuid(Map<String, Object> row, String column) {
        String value = string(row, column);
        return value == null ? null : UUID.fromString(value);
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static ChapterAdaptationException incomplete() {
        return failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
    }

    private static ChapterAdaptationException failure(ErrorCode code) {
        return new ChapterAdaptationException(code);
    }
}
