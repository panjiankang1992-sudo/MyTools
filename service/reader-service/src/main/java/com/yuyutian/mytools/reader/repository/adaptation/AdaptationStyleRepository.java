package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.*;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 风格版本只追加；管理发布和业务冻结不覆盖旧任务的模板。 */
@Repository
public class AdaptationStyleRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    /** 注入最小范围事务与模板存储。 */
    public AdaptationStyleRepository(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.transaction = new TransactionTemplate(manager);
    }

    /** 返回当前公开模板，不包含提示词正文。 */
    public Catalog catalog() {
        return new Catalog(jdbc.query("""
                SELECT r.* FROM adaptation_style_template_revision r JOIN adaptation_style_template h
                ON h.code = r.template_code AND h.latest_version = r.version
                ORDER BY CASE WHEN h.code = 'free-rewrite' THEN 0 ELSE 1 END, h.code
                """, (row, index) -> new Summary(row.getString("template_code"), row.getInt("version"),
                row.getString("name"), row.getString("description"), row.getString("prompt_sha256"))));
    }

    /** 读取明确的不可变已发布修订，不能替换成后台最新版本。 */
    public Snapshot snapshot(String code, long version) {
        requireSelection(code, version);
        return jdbc.query("SELECT * FROM adaptation_style_template_revision WHERE template_code = ? AND version = ?",
                (row, index) -> new Snapshot(new Summary(row.getString("template_code"), row.getInt("version"),
                        row.getString("name"), row.getString("description"), row.getString("prompt_sha256")),
                        row.getString("prompt_text")), code, version).stream().findFirst()
                .orElseThrow(() -> failure(ErrorCode.ADAPTATION_TEMPLATE_NOT_FOUND));
    }

    /** 管理发布串行化仅锁模板管理行，不影响普通阅读或调用外部模型。 */
    public Snapshot publish(Publish input, String auditSource) {
        validate(input);
        String fingerprint = AdaptationText.fingerprint("style-publish-v1", List.of(input.code(),
                Integer.toString(input.expectedLatestVersion()), input.name(), input.description(), input.prompt()));
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM adaptation_style_publish_guard WHERE id = 1 FOR UPDATE", Integer.class);
            var receipt = jdbc.queryForList("SELECT * FROM adaptation_style_publish_receipt WHERE idempotency_key = ?",
                    input.idempotencyKey().getBytes(StandardCharsets.US_ASCII));
            if (!receipt.isEmpty()) {
                if (!fingerprint.equals(receipt.getFirst().get("request_sha256"))) throw failure(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT);
                return snapshot((String) receipt.getFirst().get("template_code"), ((Number) receipt.getFirst().get("version")).longValue());
            }
            var head = jdbc.queryForList("SELECT latest_version FROM adaptation_style_template WHERE code = ? FOR UPDATE", input.code());
            int latest = head.isEmpty() ? 0 : ((Number) head.getFirst().get("latest_version")).intValue();
            if (latest != input.expectedLatestVersion() || latest >= 1000000) throw failure(ErrorCode.ADAPTATION_TEMPLATE_VERSION_CONFLICT);
            Timestamp now = Timestamp.from(Instant.now());
            if (head.isEmpty()) jdbc.update("INSERT INTO adaptation_style_template (code, latest_version, updated_at) VALUES (?, 0, ?)", input.code(), now);
            int version = latest + 1;
            jdbc.update("""
                    INSERT INTO adaptation_style_template_revision
                    (template_code, version, name, description, prompt_text, prompt_sha256, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, input.code(), version, input.name(), input.description(), input.prompt(), AdaptationText.sha256(input.prompt()), now);
            jdbc.update("UPDATE adaptation_style_template SET latest_version = ?, updated_at = ? WHERE code = ?", version, now, input.code());
            jdbc.update("""
                    INSERT INTO adaptation_style_publish_receipt
                    (idempotency_key, request_sha256, template_code, version, audit_source, created_at) VALUES (?, ?, ?, ?, ?, ?)
                    """, input.idempotencyKey().getBytes(StandardCharsets.US_ASCII), fingerprint, input.code(), version, auditSource, now);
            return snapshot(input.code(), version);
        });
    }

    /** 将完整风格与本次意图一起封存，原用户意图另列保存。 */
    public static String generationIntent(Snapshot snapshot, String intent) {
        verify(snapshot);
        return "Selected writing style: " + snapshot.template().name() + "\nStyle guidance (subordinate to plot continuity):\n"
                + snapshot.prompt() + "\nCurrent user intent:\n" + intent;
    }

    /** 校验持久快照摘要，避免执行过程中模板漂移或存储损坏。 */
    public static void verify(Snapshot snapshot) {
        if (snapshot == null || snapshot.template() == null || snapshot.prompt() == null
                || !AdaptationText.sha256(snapshot.prompt()).equals(snapshot.template().promptSha256()))
            throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE);
    }

    /** 从业务版本读取历史风格摘要，旧版本返回空值。 */
    public Snapshot parseSnapshot(Object value) {
        if (value == null) return null;
        String raw = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        try {
            var node = mapper.readTree(raw);
            Snapshot snapshot = mapper.readValue(node.isTextual() ? node.textValue() : raw, Snapshot.class);
            verify(snapshot); return snapshot;
        } catch (JsonProcessingException exception) { throw failure(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE); }
    }

    /** API 与领域层复用代码及版本的边界校验。 */
    public static void requireSelection(String code, long version) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{0,63}") || version < 1 || version > 1000000)
            throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
    }

    private static void validate(Publish input) {
        if (input == null || input.expectedLatestVersion() < 0) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
        requireSelection(input.code(), (long) input.expectedLatestVersion() + 1);
        AdaptationText.requireIdempotencyKey(input.idempotencyKey());
        AdaptationText.requireText(input.name(), 1, 80, ErrorCode.ADAPTATION_REQUEST_INVALID);
        AdaptationText.requireText(input.description(), 1, 500, ErrorCode.ADAPTATION_REQUEST_INVALID);
        AdaptationText.requireText(input.prompt(), 5, 6000, ErrorCode.ADAPTATION_REQUEST_INVALID);
        if (input.name().isBlank() || input.description().isBlank() || input.prompt().isBlank()) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
    }
    private static ChapterAdaptationException failure(ErrorCode code) { return new ChapterAdaptationException(code); }
}
