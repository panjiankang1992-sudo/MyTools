package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels.*;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationDisclosurePayload;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;
import java.util.UUID;

/** 用户明确授权的唯一写入口；修订和不可变事件与当前同意投影原子提交。 */
@Repository
public class AdaptationConsentRepository {
    private final JdbcTemplate jdbc;
    private final ReaderAdaptationProperties properties;
    private final AdaptationDisclosurePayload payloads;
    private final TransactionTemplate transaction;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    /** 注入当前发布配置，不从客户端接受 Provider、权利文本或告知内容。 */
    @Autowired
    public AdaptationConsentRepository(JdbcTemplate jdbc, ReaderAdaptationProperties properties,
                                        ObjectMapper mapper, PlatformTransactionManager manager) {
        this(jdbc, properties, mapper, manager, Clock.systemUTC());
    }

    /** 使用确定性时钟覆盖迟到同意、撤销和事件回滚。 */
    public AdaptationConsentRepository(JdbcTemplate jdbc, ReaderAdaptationProperties properties,
                                        ObjectMapper mapper, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.properties = properties; this.payloads = new AdaptationDisclosurePayload(mapper); this.clock = clock;
        transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot = new TransactionTemplate(manager); snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); snapshot.setReadOnly(true);
    }

    /** 关闭读写能力仍可查询当前账户的授权状态；查询不创建状态行。 */
    public Features features(long owner) { outside(owner); return snapshot.execute(ignored -> view(owner)); }

    /** 精确匹配刚展示的版本、摘要和修订；只有明确勾选两个声明才记录同意。 */
    public Features accept(long owner, Accept input) {
        outside(owner); validate(input);
        return transaction.execute(ignored -> {
            long revision = lock(owner, input.expectedConsentRevision());
            if (!properties.readEnabled()) throw failure(ErrorCode.ADAPTATION_UNAVAILABLE);
            Disclosure disclosure = disclosure(true);
            if (disclosure == null || !disclosure.version().equals(input.disclosureVersion())
                    || !disclosure.disclosureSha256().equals(input.disclosureSha256())) throw failure(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED);
            Map<String, Object> consent = one("SELECT * FROM reader_adaptation_provider_consent WHERE owner_id = ? AND disclosure_version = ? FOR UPDATE", owner, binary(disclosure.version()));
            if (consent != null && consent.get("revoked_at") == null && currentGrant(owner, revision, disclosure.version())) return view(owner);
            Instant now = now();
            if (consent == null) jdbc.update("""
                    INSERT INTO reader_adaptation_provider_consent (owner_id, disclosure_version, disclosure_sha256,
                        rights_attestation_version, accepted_at, revoked_at, audit_source) VALUES (?, ?, ?, ?, ?, NULL, 'APP_EXPLICIT')
                    """, owner, binary(disclosure.version()), disclosure.disclosureSha256(), binary(disclosure.rightsAttestationVersion()), time(now));
            else jdbc.update("""
                    UPDATE reader_adaptation_provider_consent SET accepted_at = ?, revoked_at = NULL, audit_source = 'APP_EXPLICIT'
                    WHERE owner_id = ? AND disclosure_version = ?
                    """, time(now), owner, binary(disclosure.version()));
            advance(owner, revision, "ACCEPT", disclosure, now);
            return view(owner);
        });
    }

    /** 撤销所有告知版本，不受当前发布和新建开关影响，也不删除已生成的历史正文。 */
    public Features revoke(long owner, long expectedRevision) {
        outside(owner); revision(expectedRevision);
        return transaction.execute(ignored -> {
            long previous = lock(owner, expectedRevision); Instant now = now();
            jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = ? WHERE owner_id = ? AND revoked_at IS NULL", time(now), owner);
            // 即使从未同意也推进修订，使撤销先于迟到首次同意时仍具有屏障。
            advance(owner, previous, "REVOKE_ALL", null, now);
            return view(owner);
        });
    }

    private Features view(long owner) {
        var state = one("SELECT revision FROM reader_adaptation_consent_state WHERE owner_id = ?", owner);
        long revision = state == null ? 0 : ((Number) state.get("revision")).longValue();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reader_adaptation_provider_consent WHERE owner_id = ? AND revoked_at IS NULL", Integer.class, owner);
        Disclosure disclosure;
        try { disclosure = disclosure(false); }
        catch (ChapterAdaptationException exception) {
            return new Features(properties.readEnabled(), false, "UNAVAILABLE", revision, count != null && count > 0, null, exception.errorCode().code(), null);
        }
        if (disclosure == null) return new Features(properties.readEnabled(), false, "UNAVAILABLE", revision, count != null && count > 0,
                null, ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED.code(), null);
        var consent = one("SELECT accepted_at, revoked_at FROM reader_adaptation_provider_consent WHERE owner_id = ? AND disclosure_version = ?", owner, binary(disclosure.version()));
        boolean accepted = consent != null && consent.get("revoked_at") == null && currentGrant(owner, revision, disclosure.version());
        return new Features(properties.readEnabled(), properties.readEnabled() && properties.createEnabled(), accepted ? "ACCEPTED" : "REQUIRED",
                revision, count != null && count > 0, accepted ? ((Timestamp) consent.get("accepted_at")).toInstant() : null,
                accepted ? null : ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED.code(), disclosure);
    }

    /** 新版改编只依赖功能开关与已启用的模型部署，不要求额外勾选授权。 */
    public Features creationFeatures(long owner) {
        outside(owner);
        return snapshot.execute(ignored -> {
            var deployment = one("SELECT enabled FROM novel_adaptation_provider_deployment WHERE id = ?",
                    binary(properties.providerDeploymentId()));
            boolean available = deployment != null && Boolean.TRUE.equals(deployment.get("enabled"));
            return new Features(properties.readEnabled(), properties.readEnabled() && properties.createEnabled() && available,
                    "NOT_REQUIRED", 0, false, null, available ? null : ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE.code(), null);
        });
    }

    private Disclosure disclosure(boolean locking) {
        var row = one("SELECT * FROM reader_adaptation_provider_disclosure WHERE version = ?" + (locking ? " FOR UPDATE" : ""), binary(properties.disclosureVersion()));
        if (row == null || !Boolean.TRUE.equals(row.get("enabled"))) return null;
        var deployment = one("SELECT * FROM novel_adaptation_provider_deployment WHERE id = ?", binary(properties.providerDeploymentId()));
        if (deployment == null || !Boolean.TRUE.equals(deployment.get("enabled"))) throw failure(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE);
        var payload = payloads.parse(row.get("payload_json"), (String) row.get("disclosure_sha256"), (String) deployment.get("provider_code"), (String) deployment.get("contract_sha256"));
        String rights = new String((byte[]) row.get("rights_attestation_version"), StandardCharsets.UTF_8);
        if (!rights.matches("[A-Za-z0-9_.:-]{1,64}")) throw failure(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED);
        return new Disclosure(properties.disclosureVersion(), (String) row.get("disclosure_sha256"), rights, payload);
    }

    private boolean currentGrant(long owner, long revision, String version) {
        if (revision == 0) return false;
        var event = one("SELECT disclosure_version FROM reader_adaptation_consent_event WHERE owner_id = ? AND revision = ? AND operation_kind = 'ACCEPT'", owner, revision);
        return event != null && java.util.Arrays.equals(binary(version), (byte[]) event.get("disclosure_version"));
    }

    private long lock(long owner, long expected) {
        try { jdbc.update("INSERT INTO reader_chapter_preparation_owner_guard (owner_id) VALUES (?)", owner); }
        catch (DuplicateKeyException exception) { /* 复用与创建同序的用户锁。 */ }
        jdbc.queryForObject("SELECT owner_id FROM reader_chapter_preparation_owner_guard WHERE owner_id = ? FOR UPDATE", Long.class, owner);
        var state = one("SELECT revision FROM reader_adaptation_consent_state WHERE owner_id = ? FOR UPDATE", owner);
        long current = state == null ? 0 : ((Number) state.get("revision")).longValue();
        if (expected != current) throw failure(ErrorCode.ADAPTATION_CONSENT_REVISION_CONFLICT);
        if (current == 9007199254740991L) throw failure(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED);
        if (state == null) jdbc.update("INSERT INTO reader_adaptation_consent_state (owner_id, revision, updated_at) VALUES (?, 0, ?)", owner, time(now()));
        return current;
    }

    private void advance(long owner, long previous, String operation, Disclosure disclosure, Instant now) {
        int changed = jdbc.update("UPDATE reader_adaptation_consent_state SET revision = ?, updated_at = ? WHERE owner_id = ? AND revision = ?", previous + 1, time(now), owner, previous);
        if (changed != 1) throw failure(ErrorCode.ADAPTATION_CONSENT_REVISION_CONFLICT);
        jdbc.update("""
                INSERT INTO reader_adaptation_consent_event (id, owner_id, revision, operation_kind, disclosure_version,
                    disclosure_sha256, rights_attestation_version, audit_source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'APP_EXPLICIT', ?)
                """, UUID.randomUUID().toString(), owner, previous + 1, operation, disclosure == null ? null : binary(disclosure.version()),
                disclosure == null ? null : disclosure.disclosureSha256(), disclosure == null ? null : binary(disclosure.rightsAttestationVersion()), time(now));
    }
    private static void validate(Accept input) {
        if (input == null || !input.accepted() || !input.rightsAttested() || input.disclosureVersion() == null
                || !input.disclosureVersion().matches("[A-Za-z0-9_.:-]{1,64}")) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
        AdaptationText.requireSha256(input.disclosureSha256(), ErrorCode.ADAPTATION_REQUEST_INVALID); revision(input.expectedConsentRevision());
    }
    private static void revision(long value) { if (value < 0 || value > 9007199254740991L) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID); }
    private static void outside(long owner) {
        if (owner < 1) throw failure(ErrorCode.ADAPTATION_REQUEST_INVALID);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure(ErrorCode.ADAPTATION_EXECUTION_FENCED);
    }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private Map<String, Object> one(String sql, Object... args) { var rows = jdbc.queryForList(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    private static byte[] binary(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private static ChapterAdaptationException failure(ErrorCode code) { return new ChapterAdaptationException(code); }
}
