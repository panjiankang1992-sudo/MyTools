package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextPlan;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationSourceCheck;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 持久化有界来源核验队列；网络在锁外执行，迟到任务不能覆盖新一轮结果。 */
@Repository
public class AdaptationSourceCheckRepository {
    private final JdbcTemplate jdbc;
    private final AdaptationContextRepository contexts;
    private final ReaderAdaptationProperties properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /** 注入与上下文冻结共享的规则及独立短事务。 */
    @Autowired
    public AdaptationSourceCheckRepository(JdbcTemplate jdbc, AdaptationContextRepository contexts,
            ReaderAdaptationProperties properties, ObjectMapper mapper, PlatformTransactionManager manager) {
        this(jdbc, contexts, properties, mapper, manager, Clock.systemUTC());
    }

    /** 允许测试精确推进核验过期及旧领取边界。 */
    public AdaptationSourceCheckRepository(JdbcTemplate jdbc, AdaptationContextRepository contexts,
            ReaderAdaptationProperties properties, ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.contexts = contexts; this.properties = properties; this.mapper = mapper; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 同一业务版本合并在途检查及短期成功结果，不分配新的业务版本号。 */
    public AdaptationSourceCheck request(long owner, UUID id) {
        requireOutside();
        return transaction.execute(ignored -> {
            // 容量锁先于范围锁，仅请求与领取持有；所有使用方遵循同一顺序。
            jdbc.queryForObject("SELECT id FROM novel_adaptation_source_check_capacity WHERE id = 1 FOR UPDATE", Integer.class);
            Map<String, Object> row = lockVersion(owner, id);
            requireSelected(row);
            AdaptationSourceCheck previous = projection(row, check(owner, id));
            if (List.of("QUEUED", "CHECKING", "CURRENT").contains(previous.status())) return previous;
            contexts.sourceVerificationPlan(row);
            Instant now = clock.instant();
            Integer active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM novel_adaptation_source_check WHERE status IN ('QUEUED', 'CHECKING')
                        AND deadline_at > ? AND (claimed_until IS NULL OR claimed_until > ?)
                    """, Integer.class, time(now), time(now));
            Integer own = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM novel_adaptation_source_check WHERE owner_id = ? AND status IN ('QUEUED', 'CHECKING')
                        AND deadline_at > ? AND (claimed_until IS NULL OR claimed_until > ?)
                    """, Integer.class, owner, time(now), time(now));
            if (active == null || own == null || active >= 32 || own >= 2) throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
            String checkId = UUID.randomUUID().toString();
            int changed = jdbc.update("""
                    UPDATE novel_adaptation_source_check SET check_id = ?, status = 'QUEUED', requested_at = ?, deadline_at = ?,
                        claimed_until = NULL, checked_at = NULL, valid_until = NULL, manifest_sha256 = NULL,
                        content_tokens_json = NULL, reason_code = NULL WHERE adaptation_id = ? AND owner_id = ?
                    """, checkId, time(now), time(now.plusSeconds(900)), id.toString(), owner);
            if (changed == 0) jdbc.update("""
                    INSERT INTO novel_adaptation_source_check (adaptation_id, owner_id, check_id, status, requested_at, deadline_at)
                    VALUES (?, ?, ?, 'QUEUED', ?, ?)
                    """, id.toString(), owner, checkId, time(now), time(now.plusSeconds(900)));
            return projection(row, check(owner, id));
        });
    }

    /** 状态只读取数据库；缓存相同没有核验收据时仍然是 UNKNOWN。 */
    public AdaptationSourceCheck status(long owner, UUID id) {
        requireOutside();
        return transaction.execute(ignored -> projection(lockVersion(owner, id), check(owner, id)));
    }

    /** 单次领取不重试外部调用；进程重启后过期检查显示 UNKNOWN，用户可重新核验。 */
    public AdaptationSourceCheck.Claim claim(int leaseSeconds) {
        requireOutside();
        if (leaseSeconds < 30 || leaseSeconds > 800) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
        return transaction.execute(ignored -> {
            jdbc.queryForObject("SELECT id FROM novel_adaptation_source_check_capacity WHERE id = 1 FOR UPDATE", Integer.class);
            Map<String, Object> pending = one("""
                    SELECT q.* FROM novel_adaptation_source_check q JOIN novel_chapter_adaptation a ON a.id = q.adaptation_id
                    JOIN shelf_book s ON s.id = a.shelf_book_id AND s.owner_id = a.owner_id
                    WHERE q.status = 'QUEUED' AND q.deadline_at > ? AND a.deletion_state = 'LIVE' AND s.deleted = FALSE
                    ORDER BY q.requested_at, q.adaptation_id LIMIT 1
                    """, time(clock.instant()));
            if (pending == null) return null;
            long owner = number(pending, "owner_id"); UUID id = uuid(pending, "adaptation_id");
            lockVersion(owner, id);
            Instant until = clock.instant().plusSeconds(leaseSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (until.isAfter(instant(pending, "deadline_at"))) until = instant(pending, "deadline_at");
            jdbc.update("UPDATE novel_adaptation_source_check SET status = 'CHECKING', claimed_until = ? WHERE adaptation_id = ? AND check_id = ?",
                    time(until), id.toString(), pending.get("check_id"));
            return new AdaptationSourceCheck.Claim(owner, id, uuid(pending, "check_id"), until);
        });
    }

    /** 每次可信网络读取前重新验证当前范围、删除 epoch、采用结果及领取期限。 */
    public AdaptationContextPlan plan(AdaptationSourceCheck.Claim claim) {
        requireOutside();
        return transaction.execute(ignored -> {
            Map<String, Object> row = guarded(claim);
            requireSelected(row);
            return contexts.sourceVerificationPlan(row);
        });
    }

    /** 只有完整目标章、两侧约束及目录身份一致，才提交最长六十秒的 CURRENT。 */
    public void complete(AdaptationSourceCheck.Claim claim, AdaptationContextPlan plan, List<ShelfChapterModels.Content> contents) {
        requireOutside();
        transaction.executeWithoutResult(ignored -> {
            Map<String, Object> row = guarded(claim);
            requireSelected(row);
            String manifest = contexts.verifySourceContents(row, plan, contents);
            var tokens = mapper.createObjectNode();
            for (var content : contents) tokens.put(content.chapterId().toString(), content.sha256());
            String json;
            try { json = mapper.writeValueAsString(tokens); }
            catch (JsonProcessingException exception) { throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE); }
            Instant now = clock.instant();
            if (!claim.deadline().isAfter(now)) throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
            jdbc.update("""
                    UPDATE novel_adaptation_source_check SET status = 'CURRENT', checked_at = ?, valid_until = ?,
                        manifest_sha256 = ?, content_tokens_json = ?, reason_code = NULL WHERE adaptation_id = ? AND check_id = ?
                    """, time(now), time(now.plusSeconds(60)), manifest, json, claim.adaptationId().toString(), claim.checkId().toString());
        });
    }

    /** 失败仅记录固定错误码，不保存外部异常、正文或地址；旧领取不能回写。 */
    public void fail(AdaptationSourceCheck.Claim claim, ErrorCode error) {
        requireOutside();
        String state = error == ErrorCode.CHAPTER_CATALOG_STALE || error == ErrorCode.CHAPTER_SOURCE_CHANGED ? "STALE" : "UNKNOWN";
        jdbc.update("""
                UPDATE novel_adaptation_source_check SET status = ?, checked_at = ?, valid_until = NULL, reason_code = ?
                WHERE adaptation_id = ? AND owner_id = ? AND check_id = ? AND status = 'CHECKING' AND claimed_until > ?
                """, state, time(clock.instant()), error.code(), claim.adaptationId().toString(), claim.ownerId(),
                claim.checkId().toString(), time(clock.instant()));
    }

    private AdaptationSourceCheck projection(Map<String, Object> row, Map<String, Object> check) {
        String status = check == null ? "UNKNOWN" : (String) check.get("status");
        Instant checked = check == null ? null : instant(check, "checked_at");
        Instant valid = check == null ? null : instant(check, "valid_until");
        String reason = check == null ? null : (String) check.get("reason_code");
        if (check != null && (("QUEUED".equals(status) && !instant(check, "deadline_at").isAfter(clock.instant()))
                || ("CHECKING".equals(status) && !instant(check, "claimed_until").isAfter(clock.instant()))
                || ("CURRENT".equals(status) && (valid == null || !valid.isAfter(clock.instant()))))) status = "UNKNOWN";
        if ("CURRENT".equals(status)) {
            try {
                requireSelected(row);
                var plan = contexts.sourceVerificationPlan(row);
                if (!row.get("context_manifest_sha256").equals(check.get("manifest_sha256")) || !currentTokens(row, check, plan)) status = "UNKNOWN";
            } catch (ChapterAdaptationException exception) {
                status = exception.errorCode() == ErrorCode.CHAPTER_CATALOG_STALE || exception.errorCode() == ErrorCode.CHAPTER_SOURCE_CHANGED ? "STALE" : "UNKNOWN";
                reason = exception.errorCode().code();
            }
        }
        boolean current = "CURRENT".equals(status);
        return new AdaptationSourceCheck(uuid(row, "id"), status, checked, current ? valid : null,
                List.of("QUEUED", "CHECKING").contains(status) ? 1500 : 0, reason,
                number(row, "expected_binding_revision"), number(row, "expected_catalog_revision"),
                current ? (String) row.get("original_content_sha256") : null);
    }

    private boolean currentTokens(Map<String, Object> row, Map<String, Object> check, AdaptationContextPlan plan) {
        try {
            Object raw = check.get("content_tokens_json");
            if (raw == null) return false;
            String json = raw instanceof byte[] bytes ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8) : raw.toString();
            var tokens = mapper.readTree(json);
            if (tokens.isTextual()) tokens = mapper.readTree(tokens.textValue());
            if (!tokens.isObject() || tokens.isEmpty() || tokens.size() > 3) return false;
            var expected = new java.util.HashSet<String>();
            expected.add(plan.identity().targetChapterId().toString());
            if (plan.identity().previousChapterId() != null) expected.add(plan.identity().previousChapterId().toString());
            if (plan.identity().nextChapterId() != null) expected.add(plan.identity().nextChapterId().toString());
            if (tokens.size() != expected.size()) return false;
            var fields = tokens.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!expected.remove(entry.getKey())) return false;
                Map<String, Object> chapter = one("SELECT content_sha256 FROM shelf_book_chapter WHERE id = ? AND owner_id = ? AND shelf_book_id = ? AND active = TRUE",
                        entry.getKey(), row.get("owner_id"), row.get("shelf_book_id"));
                if (chapter == null || !entry.getValue().isTextual() || !entry.getValue().textValue().equals(chapter.get("content_sha256"))) return false;
            }
            return expected.isEmpty();
        } catch (JsonProcessingException exception) { return false; }
    }

    private Map<String, Object> guarded(AdaptationSourceCheck.Claim claim) {
        Map<String, Object> row = lockVersion(claim.ownerId(), claim.adaptationId());
        Map<String, Object> check = check(claim.ownerId(), claim.adaptationId());
        if (check == null || !claim.checkId().toString().equals(check.get("check_id")) || !"CHECKING".equals(check.get("status"))
                || !claim.deadline().equals(instant(check, "claimed_until")) || !claim.deadline().isAfter(clock.instant())) throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
        return row;
    }

    private Map<String, Object> lockVersion(long owner, UUID id) {
        if (!properties.readEnabled()) throw failure(ErrorCode.ADAPTATION_UNAVAILABLE);
        Map<String, Object> known = one("SELECT * FROM novel_chapter_adaptation WHERE id = ? AND owner_id = ?", id.toString(), owner);
        if (known == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        Map<String, Object> shelf = one("SELECT id FROM shelf_book WHERE id = ? AND owner_id = ? AND deleted = FALSE FOR UPDATE", known.get("shelf_book_id"), owner);
        if (shelf == null) throw failure(ErrorCode.ADAPTATION_NOT_FOUND);
        one("SELECT id FROM shelf_book_content_binding WHERE id = ? AND owner_id = ? FOR UPDATE", known.get("content_binding_id"), owner);
        Map<String, Object> chapter = one("SELECT adaptation_delete_epoch FROM shelf_book_chapter WHERE id = ? AND owner_id = ? FOR UPDATE", known.get("chapter_id"), owner);
        Map<String, Object> row = one("SELECT * FROM novel_chapter_adaptation WHERE id = ? AND owner_id = ? FOR UPDATE", id.toString(), owner);
        if (row == null || !"LIVE".equals(row.get("deletion_state")) || chapter == null
                || number(chapter, "adaptation_delete_epoch") != number(row, "chapter_delete_epoch")) throw failure(ErrorCode.ADAPTATION_HISTORY_DELETED);
        return row;
    }

    private void requireSelected(Map<String, Object> row) {
        if (!"COMPLETED".equals(row.get("status")) || !"SEALED".equals(row.get("context_status"))
                || one(ChapterAdaptationRepository.SELECTED_RESULT, row.get("id"), row.get("owner_id")) == null) throw failure(ErrorCode.ADAPTATION_PARENT_INVALID);
    }
    private Map<String, Object> check(long owner, UUID id) { return one("SELECT * FROM novel_adaptation_source_check WHERE adaptation_id = ? AND owner_id = ?", id.toString(), owner); }
    private Map<String, Object> one(String sql, Object... args) { var rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static UUID uuid(Map<String, Object> row, String key) { return UUID.fromString((String) row.get(key)); }
    private static Instant instant(Map<String, Object> row, String key) { return row.get(key) == null ? null : ((Timestamp) row.get(key)).toInstant(); }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private static void requireOutside() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED); }
    private static ChapterAdaptationException failure(ErrorCode error) { return new ChapterAdaptationException(error); }
}
