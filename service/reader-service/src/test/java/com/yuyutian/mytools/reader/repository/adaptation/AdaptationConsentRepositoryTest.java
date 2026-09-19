package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.controller.ReaderExceptionHandler;
import com.yuyutian.mytools.reader.controller.adaptation.AdaptationConsentController;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels.*;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationConsentFixtures;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationDisclosurePayload;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import org.flywaydb.core.Flyway;
import org.h2.api.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实迁移与短事务核对明确同意、修订屏障、审计回滚及私网 HTTP；全部文案为虚构夹具。 */
class AdaptationConsentRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final String CONTRACT = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AdaptationDisclosurePayload payloads = new AdaptationDisclosurePayload(mapper);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private AdaptationConsentRepository repository;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:consent_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        jdbc.update("INSERT INTO novel_adaptation_provider_deployment VALUES (?, 'fixture', ?, 1, ?, TRUE, ?)", binary("fixture"), binary("fixture-model"), CONTRACT, Timestamp.from(NOW));
        publish("v1"); repository = repository("v1", true);
    }

    @AfterEach
    void close() { if (jdbc != null) jdbc.execute("SHUTDOWN"); }

    @Test
    void readsDoNotGrantConsentAndExplicitAcceptCreatesOneAuditEvent() {
        var before = repository.features(41);
        assertThat(before.consentStatus()).isEqualTo("REQUIRED"); assertThat(before.consentRevision()).isZero();
        assertThat(count("reader_adaptation_consent_state")).isZero(); assertThat(count("reader_adaptation_provider_consent")).isZero();
        var accepted = repository.accept(41, input(0));
        assertThat(accepted.consentStatus()).isEqualTo("ACCEPTED"); assertThat(accepted.consentRevision()).isEqualTo(1);
        assertThat(accepted.acceptedAt()).isEqualTo(NOW); assertThat(accepted.hasActiveConsent()).isTrue();
        assertThat(repository.accept(41, input(1))).isEqualTo(accepted);
        assertThat(count("reader_adaptation_consent_event")).isEqualTo(1);
        assertThat(repository.features(42).consentStatus()).isEqualTo("REQUIRED");
    }

    @Test
    void directFeaturesDoNotDependOnDisclosureOrGrantConsent() {
        repository = new AdaptationConsentRepository(jdbc,
                new ReaderAdaptationProperties(true, true, "fixture", "", "fixture", "fixture", 2), mapper, manager, clock);
        jdbc.update("UPDATE reader_adaptation_provider_disclosure SET enabled = FALSE");
        var direct = repository.creationFeatures(41);
        assertThat(direct.consentStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(direct.createEnabled()).isTrue();
        assertThat(direct.hasActiveConsent()).isFalse();
        assertThat(direct.disclosure()).isNull();
        assertThat(count("reader_adaptation_consent_event")).isZero();
        jdbc.update("UPDATE novel_adaptation_provider_deployment SET enabled = FALSE");
        assertThat(repository.creationFeatures(41).createEnabled()).isFalse();
    }

    @Test
    void rejectsFalseMissingMismatchedAndStaleDeclarationsWithoutAudit() {
        for (var input : List.of(new Accept("v1", sha(), false, true, 0), new Accept("v1", sha(), true, false, 0),
                new Accept("v1", sha(), true, true, -1))) expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> repository.accept(41, input));
        expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> repository.accept(41, new Accept("v2", sha(), true, true, 0)));
        expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> repository.accept(41, new Accept("v1", CONTRACT, true, true, 0)));
        expect(ErrorCode.ADAPTATION_CONSENT_REVISION_CONFLICT, () -> repository.accept(41, input(1)));
        assertThat(count("reader_adaptation_consent_event")).isZero();
    }

    @Test
    void revokeBeforeFirstAcceptBlocksLateRequestAndReacceptCreatesNewGrant() {
        var revoked = repository.revoke(41, 0);
        assertThat(revoked.consentRevision()).isEqualTo(1); assertThat(revoked.hasActiveConsent()).isFalse();
        expect(ErrorCode.ADAPTATION_CONSENT_REVISION_CONFLICT, () -> repository.accept(41, input(0)));
        repository.accept(41, input(1)); repository.revoke(41, 2);
        expect(ErrorCode.ADAPTATION_CONSENT_REVISION_CONFLICT, () -> repository.accept(41, input(1)));
        assertThat(repository.accept(41, input(3)).consentRevision()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT operation_kind FROM reader_adaptation_consent_event ORDER BY revision", String.class))
                .containsExactly("REVOKE_ALL", "ACCEPT", "REVOKE_ALL", "ACCEPT");
    }

    @Test
    void newDisclosureRequiresNewGrantAndRevocationWorksWithFeatureDisabled() {
        repository.accept(41, input(0)); publish("v2"); var next = repository("v2", true);
        assertThat(next.features(41).consentStatus()).isEqualTo("REQUIRED");
        next.accept(41, new Accept("v2", sha(), true, true, 1));
        assertThat(repository.features(41).consentStatus()).isEqualTo("REQUIRED");
        var disabled = repository("unpublished", false);
        assertThat(disabled.features(41).consentStatus()).isEqualTo("UNAVAILABLE");
        assertThat(disabled.revoke(41, 2).hasActiveConsent()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_adaptation_provider_consent WHERE revoked_at IS NOT NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void oldTasksCannotRegainSendPermissionAfterRevocationAndReacceptance() {
        repository.accept(41, input(0));
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(ignored -> assertThat(AdaptationConsentGate.require(jdbc, mapper, 41, binary("v1"), binary("fixture"), 1L)).isEqualTo(1));
        repository.revoke(41, 1); repository.accept(41, input(2));
        transaction.executeWithoutResult(ignored -> expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED,
                () -> AdaptationConsentGate.require(jdbc, mapper, 41, binary("v1"), binary("fixture"), 1L)));
        transaction.executeWithoutResult(ignored -> assertThat(AdaptationConsentGate.require(jdbc, mapper, 41, binary("v1"), binary("fixture"), 3L)).isEqualTo(3));
    }

    @Test
    void legacyUnauditedConsentIsNotTreatedAsAValidCurrentGrant() {
        jdbc.update("INSERT INTO reader_adaptation_provider_consent VALUES (41, ?, ?, ?, ?, NULL, 'APP_EXPLICIT')", binary("v1"), sha(), binary("rights-v1"), Timestamp.from(NOW));
        assertThat(repository.features(41).consentStatus()).isEqualTo("REQUIRED");
        new TransactionTemplate(manager).executeWithoutResult(ignored -> expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED,
                () -> AdaptationConsentGate.require(jdbc, mapper, 41, binary("v1"), binary("fixture"), null)));
        assertThat(repository.accept(41, input(0)).consentRevision()).isEqualTo(1);
    }

    @Test
    void eventWriteFailureRollsBackConsentAndRevisionTogether() {
        jdbc.execute("CREATE TRIGGER fail_consent_event BEFORE INSERT ON reader_adaptation_consent_event FOR EACH ROW CALL '" + RejectEvent.class.getName() + "'");
        assertThatThrownBy(() -> repository.accept(41, input(0))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("reader_adaptation_provider_consent")).isZero(); assertThat(count("reader_adaptation_consent_state")).isZero();
        jdbc.execute("DROP TRIGGER fail_consent_event"); repository.accept(41, input(0));
        jdbc.execute("CREATE TRIGGER fail_consent_event BEFORE INSERT ON reader_adaptation_consent_event FOR EACH ROW CALL '" + RejectEvent.class.getName() + "'");
        assertThatThrownBy(() -> repository.revoke(41, 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repository.features(41).consentStatus()).isEqualTo("ACCEPTED");
        assertThat(repository.features(41).consentRevision()).isEqualTo(1); assertThat(count("reader_adaptation_consent_event")).isEqualTo(1);
    }

    @Test
    void competingAcceptAndRevokeHaveOneLinearizedWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var accept = pool.submit(() -> { start.await(); return outcome(() -> repository.accept(41, input(0))); });
            var revoke = pool.submit(() -> { start.await(); return outcome(() -> repository.revoke(41, 0)); });
            start.countDown(); assertThat(List.of(accept.get(10, TimeUnit.SECONDS), revoke.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "READER_062");
        }
        assertThat(repository.features(41).consentRevision()).isEqualTo(1); assertThat(count("reader_adaptation_consent_event")).isEqualTo(1);
    }

    @Test
    void corruptOrMismatchedPublicationCannotBePresentedOrAccepted() {
        String canonical = payloads.canonical(AdaptationConsentFixtures.payload(CONTRACT));
        for (String invalid : List.of("null", "{}", canonical.replace("https://api.sillytraven.dev", "https://untrusted.invalid"),
                canonical.replace("Fixture provider", "Changed notice"), canonical.replace("{", "{\"extra\":true,"),
                canonical.replace("{", "{\"schemaVersion\":\"duplicate\","))) {
            jdbc.update("UPDATE reader_adaptation_provider_disclosure SET payload_json = ? WHERE version = ?", invalid, binary("v1"));
            assertThat(repository.features(41).consentStatus()).isEqualTo("UNAVAILABLE");
            expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> repository.accept(41, input(0)));
        }
        assertThat(count("reader_adaptation_consent_event")).isZero();
    }

    @Test
    void realReaderHttpRequiresExactBodyAndReturnsRevisionConflictWithoutLeakingPayload() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdaptationConsentController(repository, mapper))
                .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
        String root = "/api/v1/reader-state/features/reader-adaptation"; String body = mapper.writeValueAsString(input(0));
        for (String invalid : List.of(body.replace("true", "\"true\""), body.replace("{", "{\"ownerId\":99,"), body + "{}", body.replace("{", "{\"accepted\":false,"))) {
            mvc.perform(post(root + "/consent?ownerId=41").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(root + "/consent?ownerId=41").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consentRevision").value(1)).andExpect(header().string("Cache-Control", "no-store, private"));
        mvc.perform(delete(root + "/consent?ownerId=41&expectedConsentRevision=1")).andExpect(status().isOk()).andExpect(jsonPath("$.hasActiveConsent").value(false));
        mvc.perform(post(root + "/consent?ownerId=41").contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READER_062"));
        mvc.perform(get(root + "?ownerId=42")).andExpect(jsonPath("$.consentRevision").value(0));
    }

    private AdaptationConsentRepository repository(String version, boolean read) {
        return new AdaptationConsentRepository(jdbc, new ReaderAdaptationProperties(read, false, "fixture", version, "fixture", "fixture", 2), mapper, manager, clock);
    }
    private void publish(String version) {
        jdbc.update("INSERT INTO reader_adaptation_provider_disclosure VALUES (?, ?, ?, ?, TRUE, ?)", binary(version), sha(), binary("rights-v1"), payloads.canonical(AdaptationConsentFixtures.payload(CONTRACT)), Timestamp.from(NOW));
    }
    private Accept input(long revision) { return new Accept("v1", sha(), true, true, revision); }
    private String sha() { return payloads.sha256(AdaptationConsentFixtures.payload(CONTRACT)); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static byte[] binary(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void expect(ErrorCode code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ChapterAdaptationException.class, error -> assertThat(error.errorCode()).isEqualTo(code)); }
    private static String outcome(Runnable operation) { try { operation.run(); return "OK"; } catch (ChapterAdaptationException error) { return error.errorCode().code(); } }
    /** 模拟不可变审计事件写入失败，必须回滚整个授权操作。 */
    public static class RejectEvent implements Trigger {
        /** 拒绝写入，不包含用户数据。 */
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException("Fixture audit failure"); }
    }
}
