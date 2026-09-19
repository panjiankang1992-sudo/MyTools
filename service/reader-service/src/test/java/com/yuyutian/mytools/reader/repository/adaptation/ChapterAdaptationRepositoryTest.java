package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationInput;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationViews;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationService;
import com.yuyutian.mytools.reader.service.adaptation.SourceLocatorCipher;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 完整迁移上的真实事务夹具；成功候选由 SQL 建立，只验证版本库，不模拟已接通模型。 */
class ChapterAdaptationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Timestamp TIME = Timestamp.from(NOW);
    private static final String SHA = AdaptationText.sha256("Original chapter.");
    private static final String RESULT = "Enriched chapter with an unchanged outcome.";
    private final UUID shelf = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID();
    private final UUID chapter = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ChapterAdaptationRepository repository;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:adaptation_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        repository = repository(true, true, 2);
        jdbc.update("INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at) VALUES (?, 41, ?, '{}', ?, ?)",
                shelf.toString(), shelf.toString(), TIME, TIME);
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 41, ?, 'Fixture', 'https://fixture.invalid', TRUE, 1, ?, ?)
                """, source.toString(), source.toString(), TIME, TIME);
        jdbc.update("INSERT INTO book_source_version VALUES (?, 1, '{}', ?, ?)", source.toString(), SHA, TIME);
        jdbc.update("""
                INSERT INTO shelf_book_content_binding (id, owner_id, shelf_book_id, binding_type, source_id, source_version,
                    source_book_key, binding_revision, bound_shelf_version, status, catalog_revision, catalog_sha256, created_at, updated_at)
                VALUES (?, 41, ?, 'SOURCE_RUNTIME', ?, 1, ?, 1, 1, 'ACTIVE', 1, ?, ?, ?)
                """, binding.toString(), shelf.toString(), source.toString(), SHA, SHA, TIME, TIME);
        insertChapter(chapter, 0);
        jdbc.update("""
                INSERT INTO novel_adaptation_provider_deployment VALUES (?, 'fixture', ?, 1, ?, TRUE, ?)
                """, binary("fixture-deployment"), binary("Fixture-Model"), SHA, TIME);
        com.yuyutian.mytools.reader.service.adaptation.AdaptationConsentFixtures.installAndAccept(jdbc,
                "fixture-deployment", "fixture-disclosure", "fixture-rights", SHA, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void shutdown() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void shouldCreateAtomicReceiptVersionAndRootWithoutChangingOriginal() {
        var command = initial("Create-A");
        var created = repository.create(command);
        assertThat(created.revisionNumber()).isEqualTo(1);
        assertThat(created.status()).isEqualTo(AdaptationStatus.PENDING_DISPATCH);
        assertThat(created.currentStage()).isEqualTo("CONTEXT_PENDING");
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(repository.create(command)).isEqualTo(created);
        assertThat(count("novel_chapter_adaptation")).isEqualTo(1);
        assertThat(count("novel_chapter_adaptation_request_receipt")).isEqualTo(1);
        var details = repository.detail(41, created.adaptationId());
        assertThat(details.version().lineage().rootAdaptationId()).isEqualTo(created.adaptationId());
        assertThat(details.version().lineage().parentAdaptationId()).isNull();
        assertThat(details.result()).isNull();
        assertThat(details.sourceRelation()).isEqualTo("UNKNOWN");
        assertThat(details.version().modelId()).isEqualTo("Fixture-Model");
        assertThat(details.version().toString()).doesNotContain(command.intent());
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM shelf_book_chapter WHERE id = ?", String.class, chapter.toString())).isEqualTo(SHA);
        assertThat(jdbc.queryForObject("SELECT version FROM shelf_book WHERE id = ?", Long.class, shelf.toString())).isEqualTo(1);
    }

    @Test
    void freezesStyleVersionAndKeepsReceiptStableAcrossLaterPublishing() {
        var styles = new AdaptationStyleRepository(jdbc, mapper, manager);
        var seed = com.yuyutian.mytools.reader.service.adaptation.AdaptationStyleInitializer.defaults().getFirst();
        var first = styles.publish(seed, "test");
        assertThat(styles.publish(seed, "test")).isEqualTo(first);
        var old = initial("style-create");
        var command = new AdaptationCommand(old.ownerId(), old.shelfBookId(), old.chapterId(), old.kind(), old.triggerAdaptationId(),
                old.idempotencyKey(), old.intent(), old.expectedBindingRevision(), old.expectedCatalogRevision(), old.expectedSourceSha256(),
                seed.code(), 1L);
        var created = repository.create(command);
        styles.publish(new com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.Publish("update-style", seed.code(), 1,
                "Updated style", "New revision", "Use a different rhythm."), "test");
        assertThat(styles.catalog().items().getFirst().version()).isEqualTo(2);
        assertThat(repository.create(command)).isEqualTo(created);
        assertThat(repository.detail(41, created.adaptationId()).version().styleTemplate()).isEqualTo(first.template());
        var row = jdbc.queryForMap("SELECT * FROM novel_chapter_adaptation WHERE id = ?", created.adaptationId().toString());
        assertThat(row.get("generation_intent_text")).isEqualTo(AdaptationStyleRepository.generationIntent(first, old.normalizedIntent()));
        assertThat(row.get("prompt_version")).isEqualTo("novel-adaptation-v2");
        expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> repository.create(new AdaptationCommand(old.ownerId(), old.shelfBookId(), old.chapterId(), old.kind(), null,
                old.idempotencyKey(), old.intent(), 1, 1, old.expectedSourceSha256(), seed.code(), 2L)));
        expect(ErrorCode.ADAPTATION_TEMPLATE_VERSION_CONFLICT, () -> styles.publish(new com.yuyutian.mytools.reader.model.adaptation.AdaptationStyleModels.Publish(
                "stale-update", seed.code(), 1, "Another", "Another revision", "Some guidance."), "test"));
    }

    @Test
    void shouldReplayBeforeActiveCheckAndAfterCreateShutdownAndSourceChange() {
        var command = initial("repeat");
        var created = repository.create(command);
        jdbc.update("UPDATE shelf_book_content_binding SET status = 'STALE' WHERE id = ?", binding.toString());
        jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = ?", TIME);
        assertThat(repository(true, false, 2).create(command)).isEqualTo(created);
        expect(ErrorCode.ADAPTATION_UNAVAILABLE, () -> repository(true, false, 2).create(initial("new")));
    }

    @Test
    void shouldPreserveKeyCaseAndRejectChangedSemanticInput() {
        var first = repository.create(initial("Key"));
        expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> repository.create(command("Key", chapter,
                AdaptationRequestKind.INITIAL, null, "A changed intention.", 1, 1, SHA)));
        expect(ErrorCode.ADAPTATION_ALREADY_ACTIVE, () -> repository.create(initial("key")));
        repository.cancel(41, first.adaptationId());
        assertThat(repository.create(initial("key")).revisionNumber()).isEqualTo(2);
    }

    @Test
    void shouldNormalizeIntentForReplayButRetainFirstRawInput() {
        var first = command("trim", chapter, AdaptationRequestKind.INITIAL, null, "  Add restrained dialogue. \n", 1, 1, SHA);
        var second = command("trim", chapter, AdaptationRequestKind.INITIAL, null, "Add restrained dialogue.", 1, 1, SHA);
        var created = repository.create(first);
        assertThat(repository.create(second)).isEqualTo(created);
        assertThat(repository.detail(41, created.adaptationId()).version().intent()).isEqualTo(first.intent());
    }

    @Test
    void shouldSerializeConcurrentReplayIntoOneVersion() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<AdaptationViews.Accepted> task = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return repository.create(initial("concurrent"));
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("novel_chapter_adaptation")).isEqualTo(1);
    }

    @Test
    void shouldSerializeDifferentKeysOnSameChapterWithoutOrphanReceipt() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("race-a", "race-b").stream().map(key -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    repository.create(initial(key));
                    return "CREATED";
                } catch (ChapterAdaptationException exception) {
                    return exception.errorCode().code();
                }
            })).toList();
            start.countDown();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", ErrorCode.ADAPTATION_ALREADY_ACTIVE.code());
        }
        assertThat(count("novel_chapter_adaptation_request_receipt")).isEqualTo(1);
    }

    @Test
    void shouldRequirePinnedEnabledDeploymentWithoutAllocatingRevision() {
        jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = ?", TIME);
        jdbc.update("UPDATE novel_adaptation_provider_deployment SET enabled = FALSE");
        expect(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE, () -> repository.create(initial("deployment")));
        jdbc.update("UPDATE novel_adaptation_provider_deployment SET enabled = TRUE");
        assertThat(repository.create(initial("enabled")).revisionNumber()).isEqualTo(1);
        assertThat(count("novel_chapter_adaptation_request_receipt")).isEqualTo(1);
    }

    @Test
    void shouldCreateWithoutConsentAndNeverFabricateAnAcceptance() {
        jdbc.update("DELETE FROM reader_adaptation_provider_consent");
        jdbc.update("DELETE FROM reader_adaptation_consent_event");
        jdbc.update("DELETE FROM reader_adaptation_consent_state");
        var created = repository.create(initial("direct-create"));
        var row = jdbc.queryForMap("SELECT consent_policy, disclosure_version, consent_revision FROM novel_chapter_adaptation WHERE id = ?",
                created.adaptationId().toString());
        assertThat(row.get("consent_policy")).isEqualTo("DIRECT");
        assertThat(row.get("disclosure_version")).isNull();
        assertThat(((Number) row.get("consent_revision")).longValue()).isZero();
        assertThat(count("reader_adaptation_provider_consent")).isZero();
        assertThat(count("reader_adaptation_consent_event")).isZero();
    }

    @Test
    void shouldEnforceOwnerLimitAcrossChapters() {
        UUID second = UUID.randomUUID();
        insertChapter(second, 1);
        repository = repository(true, true, 1);
        repository.create(initial("one"));
        expect(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED, () -> repository.create(command("two", second,
                AdaptationRequestKind.INITIAL, null, "Keep all original outcomes.", 1, 1, SHA)));
        assertThat(count("novel_chapter_adaptation_request_receipt")).isEqualTo(1);
    }

    @Test
    void shouldRejectForeignScopeStaleCatalogAndChangedKnownContent() {
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> repository.create(new AdaptationCommand(42, shelf, chapter,
                AdaptationRequestKind.INITIAL, null, "foreign", "Keep all original outcomes.", 1, 1, SHA)));
        expect(ErrorCode.CHAPTER_CATALOG_STALE, () -> repository.create(command("revision", chapter,
                AdaptationRequestKind.INITIAL, null, "Keep all original outcomes.", 1, 2, SHA)));
        expect(ErrorCode.CHAPTER_SOURCE_CHANGED, () -> repository.create(command("hash", chapter,
                AdaptationRequestKind.INITIAL, null, "Keep all original outcomes.", 1, 1, "b".repeat(64))));
        jdbc.update("UPDATE shelf_book SET version = 2 WHERE id = ?", shelf.toString());
        expect(ErrorCode.CHAPTER_CATALOG_STALE, () -> repository.create(initial("shelf-version")));
        assertThat(count("novel_chapter_adaptation")).isZero();
    }

    @Test
    void shouldCancelNeverDispatchedAtomicallyAndKeepReceiptResponseStable() {
        var command = initial("cancel");
        var created = repository.create(command);
        var cancelled = repository.cancel(41, created.adaptationId());
        assertThat(cancelled.status()).isEqualTo(AdaptationStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(NOW);
        assertThat(cancelled.version()).isEqualTo(3);
        assertThat(repository.cancel(41, created.adaptationId())).isEqualTo(cancelled);
        assertThat(repository.create(command)).isEqualTo(created);
        assertThat(repository.create(initial("after-cancel")).revisionNumber()).isEqualTo(2);
    }

    @Test
    void shouldNotFinishCancellationWhileDispatchOutcomeIsUncertain() {
        var id = repository.create(initial("dispatch")).adaptationId();
        jdbc.update("UPDATE novel_chapter_adaptation SET dispatch_attempt_count = 1 WHERE id = ?", id.toString());
        var pending = repository.cancel(41, id);
        assertThat(pending.status()).isEqualTo(AdaptationStatus.CANCEL_REQUESTED);
        assertThat(pending.finishedAt()).isNull();
        assertThat(repository.cancel(41, id)).isEqualTo(pending);
        expect(ErrorCode.ADAPTATION_ALREADY_ACTIVE, () -> repository.create(initial("next")));
    }

    @Test
    void shouldReturnOnlySafeCandidateAndValidatedSelectedOutput() {
        var id = repository.create(initial("result")).adaptationId();
        var selected = completeFixture(id);
        var rejected = insertAttempt(id, 4, "REPAIR", true, "CONSTRAINTS", "A rejected story change.");
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_validation (id, adaptation_id, owner_id, candidate_attempt_id, critic_attempt_id,
                    validation_round, deterministic_version, deterministic_json, critic_version, critic_json, outcome, report_json, report_sha256, created_at, content_policy_outcome)
                SELECT ?, adaptation_id, owner_id, ?, critic_attempt_id, 2, deterministic_version, deterministic_json, critic_version, critic_json,
                    'BLOCKED', report_json, report_sha256, created_at, 'PASS' FROM novel_chapter_adaptation_validation WHERE adaptation_id = ?
                """, UUID.randomUUID().toString(), rejected.toString(), id.toString());
        var isolated = insertAttempt(id, 5, "REPAIR", false, "SAFETY_OR_PLATFORM", "Isolated private output.");
        var detail = repository.detail(41, id);
        assertThat(detail.result().attemptId()).isEqualTo(selected);
        assertThat(detail.result().content()).isEqualTo(RESULT);
        assertThat(detail.attempts()).extracting(AdaptationViews.Candidate::attemptId).containsExactly(selected, rejected);
        assertThat(repository.attempt(41, id, rejected).viewStatus()).isEqualTo("REJECTED_BY_CONSTRAINTS");
        expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> repository.attempt(41, id, isolated));
        UUID plan = UUID.fromString(jdbc.queryForObject("SELECT id FROM novel_chapter_adaptation_attempt WHERE adaptation_id = ? AND call_kind = 'PLAN'",
                String.class, id.toString()));
        expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> repository.attempt(41, id, plan));
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> repository.detail(42, id));
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> repository.cancel(42, id));
        assertThat(repository.cancel(41, id).status()).isEqualTo(AdaptationStatus.COMPLETED);
        jdbc.update("UPDATE novel_chapter_adaptation_validation SET outcome = 'BLOCKED' WHERE adaptation_id = ?", id.toString());
        assertThat(repository.detail(41, id).result()).isNull();
    }

    @Test
    void shouldRejectCorruptOutputAndNeverTreatCachedSourceAsCurrent() {
        var id = repository.create(initial("corrupt")).adaptationId();
        UUID candidate = completeFixture(id);
        assertThat(repository.detail(41, id).sourceRelation()).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", "b".repeat(64), chapter.toString());
        assertThat(repository.detail(41, id).sourceRelation()).isEqualTo("STALE");
        jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_text = 'Altered' WHERE id = ?", candidate.toString());
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> repository.attempt(41, id, candidate));
    }

    @Test
    void shouldHideUnknownOrBlockedContentPolicyEvenWhenCandidateFlagAndSelectionArePresent() {
        UUID adaptation = repository.create(initial("unreviewed-output")).adaptationId();
        UUID candidate = completeFixture(adaptation);
        for (String policy : List.of("UNKNOWN", "BLOCKED")) {
            jdbc.update("UPDATE novel_chapter_adaptation_validation SET content_policy_outcome = ? WHERE adaptation_id = ?", policy, adaptation.toString());
            expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> repository.attempt(41, adaptation, candidate));
            assertThat(repository.detail(41, adaptation).result()).isNull();
            assertThat(repository.detail(41, adaptation).attempts()).isEmpty();
            assertThat(repository.progress(41, adaptation).candidateCount()).isZero();
        }
    }

    @Test
    void shouldKeepOptimizeParentAndRegenerateRootAsDifferentLineage() {
        UUID root = repository.create(initial("root")).adaptationId();
        completeFixture(root);
        UUID optimized = repository.create(command("opt", chapter, AdaptationRequestKind.OPTIMIZE, root,
                "Make the dialogue restrained.", 1, 1, SHA)).adaptationId();
        var lineage = repository.detail(41, optimized).version().lineage();
        assertThat(lineage.parentAdaptationId()).isEqualTo(root);
        assertThat(lineage.triggerAdaptationId()).isEqualTo(root);
        assertThat(lineage.rootAdaptationId()).isEqualTo(root);
        completeFixture(optimized);
        UUID regenerated = repository.create(command("regen", chapter, AdaptationRequestKind.REGENERATE, optimized,
                "Add more environmental details.", 1, 1, SHA)).adaptationId();
        var branch = repository.detail(41, regenerated).version().lineage();
        assertThat(branch.rootAdaptationId()).isEqualTo(root);
        assertThat(branch.triggerAdaptationId()).isEqualTo(optimized);
        assertThat(branch.parentAdaptationId()).isNull();
        assertThat(repository.detail(41, root).result().content()).isEqualTo(RESULT);
        assertThat(repository.detail(41, optimized).result().content()).isEqualTo(RESULT);
    }

    @Test
    void shouldRejectDerivationFromFailedOrUnverifiedOrDifferentChapter() {
        UUID root = repository.create(initial("root")).adaptationId();
        repository.cancel(41, root);
        expect(ErrorCode.ADAPTATION_PARENT_INVALID, () -> repository.create(command("cancelled", chapter,
                AdaptationRequestKind.OPTIMIZE, root, "Keep all original outcomes.", 1, 1, SHA)));
        UUID completed = repository.create(initial("completed")).adaptationId();
        completeFixture(completed);
        expect(ErrorCode.CHAPTER_SOURCE_CHANGED, () -> repository.create(command("unknown", chapter,
                AdaptationRequestKind.REGENERATE, completed, "Keep all original outcomes.", 1, 1, null)));
        UUID second = UUID.randomUUID();
        insertChapter(second, 1);
        expect(ErrorCode.ADAPTATION_PARENT_INVALID, () -> repository.create(command("wrong-chapter", second,
                AdaptationRequestKind.OPTIMIZE, completed, "Keep all original outcomes.", 1, 1, SHA)));
    }

    @Test
    void shouldPageHistoryAcrossNewVersionsWithoutRepeatingItems() {
        for (int index = 0; index < 3; index++) {
            repository.cancel(41, repository.create(initial("page-" + index)).adaptationId());
        }
        var first = repository.history(41, shelf, chapter, 2, null);
        assertThat(first.items()).extracting(AdaptationViews.HistoryItem::revisionNumber).containsExactly(3L, 2L);
        assertThat(first.nextBeforeRevision()).isEqualTo(2);
        repository.create(initial("page-4"));
        var next = repository.history(41, shelf, chapter, 2, first.nextBeforeRevision());
        assertThat(next.items()).extracting(AdaptationViews.HistoryItem::revisionNumber).containsExactly(1L);
        assertThat(next.nextBeforeRevision()).isNull();
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> repository.history(42, shelf, chapter, 2, null));
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> repository.history(41, shelf, chapter, 51, null));
    }

    @Test
    void shouldKeepDeletedRequestTombstoneAndRejectChangedFingerprintBeforeGone() {
        var command = initial("deleted");
        UUID id = repository.create(command).adaptationId();
        repository.cancel(41, id);
        // 删除执行器尚未接入，此夹具只验证删除收据的读取契约。
        jdbc.update("""
                UPDATE novel_chapter_adaptation_request_receipt SET response_status = 410,
                    response_snapshot_json = '{}', adaptation_id = NULL, tombstoned_at = ? WHERE adaptation_id = ?
                """, TIME, id.toString());
        jdbc.update("UPDATE novel_chapter_adaptation SET deletion_state = 'TOMBSTONED', tombstoned_at = ? WHERE id = ?", TIME, id.toString());
        expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> repository.create(command));
        expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> repository.create(command("deleted", chapter,
                AdaptationRequestKind.INITIAL, null, "Changed required intention.", 1, 1, SHA)));
        expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> repository.detail(41, id));
        assertThat(repository.history(41, shelf, chapter, 20, null).items()).isEmpty();
    }

    @Test
    void shouldEnforceMigrationScopesRestrictAndContextShape() {
        UUID id = repository.create(initial("constraints")).adaptationId();
        assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation SET owner_id = 42 WHERE id = ?", id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shelf_book_chapter WHERE id = ?", chapter.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation SET status = 'COMPLETED', finished_at = ? WHERE id = ?", TIME, id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation SET provider_call_budget_remaining = -1 WHERE id = ?", id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation_lineage SET parent_adaptation_id = ? WHERE child_adaptation_id = ?", id.toString(), id.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepHistoryReadGateIndependentAndValidateConfiguration() {
        UUID id = repository.create(initial("gates")).adaptationId();
        assertThat(repository(true, false, 2).detail(41, id).version().adaptationId()).isEqualTo(id);
        expect(ErrorCode.ADAPTATION_UNAVAILABLE, () -> repository(false, false, 2).detail(41, id));
        assertThatThrownBy(() -> new ReaderAdaptationProperties(true, true, "", "", "prompt", "rules", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBindHistoryCursorToPurposeOwnerChapterAndExpiry() {
        for (int index = 0; index < 3; index++) {
            repository.cancel(41, repository.create(initial("cursor-" + index)).adaptationId());
        }
        var cipher = new SourceLocatorCipher("fixture", Map.of("fixture", new byte[32]));
        var service = new ChapterAdaptationService(repository, cipher,
                org.mockito.Mockito.mock(com.yuyutian.mytools.reader.service.adaptation.AdaptationSourceCheckService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var page = service.history(41, shelf, chapter, 2, null);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(service.history(41, shelf, chapter, 2, page.nextCursor()).items())
                .extracting(AdaptationViews.HistoryItem::revisionNumber).containsExactly(1L);
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> service.history(42, shelf, chapter, 2, page.nextCursor()));
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> service.history(41, shelf, UUID.randomUUID(), 2, page.nextCursor()));
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> service.history(41, shelf, chapter, 2, page.nextCursor() + "x"));
        String catalogCursor = cipher.signCursor("41:" + shelf + ":1:1:0:" + NOW.plusSeconds(900).getEpochSecond());
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> service.history(41, shelf, chapter, 2, catalogCursor));
        var expired = new ChapterAdaptationService(repository, cipher,
                org.mockito.Mockito.mock(com.yuyutian.mytools.reader.service.adaptation.AdaptationSourceCheckService.class), Clock.fixed(NOW.plusSeconds(900), ZoneOffset.UTC));
        expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> expired.history(41, shelf, chapter, 2, page.nextCursor()));
    }

    @Test
    void shouldReplayDerivedRequestAfterTriggerHistoryWasDeleted() {
        UUID root = repository.create(initial("derive-root")).adaptationId();
        completeFixture(root);
        var service = new ChapterAdaptationService(repository,
                new SourceLocatorCipher("fixture", Map.of("fixture", new byte[32])),
                org.mockito.Mockito.mock(com.yuyutian.mytools.reader.service.adaptation.AdaptationSourceCheckService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var input = new AdaptationInput("derive-receipt", "Keep every original outcome.", 1, 1, SHA);
        var created = service.derive(41, root, AdaptationRequestKind.OPTIMIZE, input);
        assertThat(service.derive(41, root, AdaptationRequestKind.OPTIMIZE, input)).isEqualTo(created);
        repository.cancel(41, created.adaptationId());
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET deletion_state = 'TOMBSTONED', tombstoned_at = ? WHERE owner_id = 41
                """, TIME);
        jdbc.update("""
                UPDATE novel_chapter_adaptation_request_receipt SET response_status = 410, response_snapshot_json = '{}',
                    adaptation_id = NULL, tombstoned_at = ? WHERE owner_id = 41
                """, TIME);
        expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> service.derive(41, root, AdaptationRequestKind.OPTIMIZE, input));
        var changed = new AdaptationInput(input.idempotencyKey(), "A different intention.", 1, 1, SHA);
        expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> service.derive(41, root, AdaptationRequestKind.OPTIMIZE, changed));
        expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> service.derive(41, root, AdaptationRequestKind.REGENERATE, input));
    }

    @Test
    void shouldRejectCrossChapterLineageAndSelectionEvenForSameOwner() {
        UUID first = repository.create(initial("scope-first")).adaptationId();
        UUID firstCandidate = completeFixture(first);
        UUID otherChapter = UUID.randomUUID();
        insertChapter(otherChapter, 1);
        UUID second = repository.create(command("scope-second", otherChapter, AdaptationRequestKind.INITIAL, null,
                "Preserve every original outcome.", 1, 1, SHA)).adaptationId();
        completeFixture(second);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE novel_chapter_adaptation_lineage SET root_adaptation_id = ?, trigger_adaptation_id = ?
                    WHERE child_adaptation_id = ?
                """, first.toString(), first.toString(), second.toString())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE novel_chapter_adaptation_selection SET candidate_attempt_id = ? WHERE adaptation_id = ?
                """, firstCandidate.toString(), second.toString())).isInstanceOf(DataIntegrityViolationException.class);
        expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> repository.attempt(41, second, firstCandidate));
    }

    @Test
    void shouldWaitForReservedAttemptEvenBeforeSchedulerBinding() {
        UUID id = repository.create(initial("reserved")).adaptationId();
        UUID attempt = insertAttempt(id, 1, "PLAN", false, null, null);
        jdbc.update("""
                UPDATE novel_chapter_adaptation_attempt SET status = 'REGISTERED', completed_at = NULL,
                    terminal_payload_sha256 = NULL WHERE id = ?
                """, attempt.toString());
        assertThat(repository.cancel(41, id).status()).isEqualTo(AdaptationStatus.CANCEL_REQUESTED);
        expect(ErrorCode.ADAPTATION_ALREADY_ACTIVE, () -> repository.create(initial("reserved-next")));
    }

    private ChapterAdaptationRepository repository(boolean read, boolean create, int maximum) {
        return new ChapterAdaptationRepository(jdbc, manager, mapper,
                new ReaderAdaptationProperties(read, create, "fixture-deployment", "fixture-disclosure", "prompt-v1", "rules-v1", maximum),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AdaptationCommand initial(String key) {
        return command(key, chapter, AdaptationRequestKind.INITIAL, null, "Keep all original outcomes.", 1, 1, SHA);
    }

    private AdaptationCommand command(String key, UUID chapterId, AdaptationRequestKind kind, UUID trigger,
                                       String intent, long bindingRevision, long catalogRevision, String sha) {
        return new AdaptationCommand(41, shelf, chapterId, kind, trigger, key, intent, bindingRevision, catalogRevision, sha);
    }

    private void insertChapter(UUID id, int index) {
        UUID locator = UUID.randomUUID();
        String key = AdaptationText.sha256(id.toString());
        jdbc.update("""
                INSERT INTO shelf_book_source_locator (id, owner_id, binding_id, binding_revision, source_version, locator_scope,
                    locator_ciphertext, locator_sha256, allowed_scheme, allowed_host, created_at)
                VALUES (?, 41, ?, 1, 1, 'CHAPTER', 'fixture-ciphertext', ?, 'https', 'fixture.invalid', ?)
                """, locator.toString(), binding.toString(), key, TIME);
        jdbc.update("""
                INSERT INTO shelf_book_chapter (id, owner_id, shelf_book_id, content_binding_id, binding_revision, catalog_revision,
                    chapter_index, chapter_key_sha256, chapter_title, locator_kind, source_locator_id, source_version,
                    content_kind, content_sha256, active, created_at, updated_at)
                VALUES (?, 41, ?, ?, 1, 1, ?, ?, 'Fixture chapter', 'SOURCE_CATALOG_KEY', ?, 1, 'TEXT', ?, TRUE, ?, ?)
                """, id.toString(), shelf.toString(), binding.toString(), index, key, locator.toString(), SHA, TIME, TIME);
    }

    private UUID completeFixture(UUID id) {
        // 此处直接建立成功历史，不能作为实际 context seal 或生成流水线验收证据。
        String kind = jdbc.queryForObject("SELECT request_kind FROM novel_chapter_adaptation WHERE id = ?", String.class, id.toString());
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET context_status = 'SEALED', context_role_count = ?, context_manifest_version = 'fixture-v1',
                    context_manifest_sha256 = ?, context_identity_json = '{}', context_sealed_at = ?, original_content_sha256 = ?,
                    base_content_sha256 = ?, status = 'COMPLETED', current_stage = 'DONE', finished_at = ?, attempt_count = 3 WHERE id = ?
                """, "OPTIMIZE".equals(kind) ? 5 : 4, SHA, TIME, SHA, SHA, TIME, id.toString());
        insertAttempt(id, 1, "PLAN", false, null, "Internal plan must not be shown.");
        UUID candidate = insertAttempt(id, 2, "GENERATE", true, null, RESULT);
        UUID critic = insertAttempt(id, 3, "CRITIC", false, null, null);
        UUID validation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_validation (id, adaptation_id, owner_id, candidate_attempt_id, critic_attempt_id,
                    validation_round, deterministic_version, deterministic_json, critic_version, critic_json, outcome, report_json, report_sha256, created_at, content_policy_outcome)
                VALUES (?, ?, 41, ?, ?, 1, 'fixture-v1', '{}', 'fixture-v1', '{}', 'PASS', '{}', ?, ?, 'PASS')
                """, validation.toString(), id.toString(), candidate.toString(), critic.toString(), SHA, TIME);
        jdbc.update("INSERT INTO novel_chapter_adaptation_selection VALUES (?, 41, ?, ?, ?)",
                id.toString(), candidate.toString(), validation.toString(), TIME);
        return candidate;
    }

    private UUID insertAttempt(UUID id, int number, String kind, boolean visible, String rejection, String output) {
        UUID attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_attempt (id, adaptation_id, owner_id, attempt_no, call_kind, task_instance_id,
                    execution_id, fencing_token, scheduler_attempt_no, provider_deployment_id, model_id, credential_generation,
                    provider_attempt_id, request_sha256, output_text, output_sha256, output_codepoint_count, status, viewable_candidate,
                    rejection_category, settlement_token_sha256, settlement_expires_at, chapter_delete_epoch, terminal_payload_sha256, created_at, completed_at)
                VALUES (?, ?, 41, ?, ?, ?, ?, 1, 1, ?, ?, 1, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, 0, ?, ?, ?)
                """, attempt.toString(), id.toString(), number, kind, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                binary("fixture-deployment"), binary("Fixture-Model"), UUID.randomUUID().toString(), SHA,
                output, output == null ? null : AdaptationText.sha256(output), output == null ? null : output.codePointCount(0, output.length()),
                visible, rejection, SHA, Timestamp.from(NOW.plusSeconds(840)), SHA, TIME, TIME);
        return attempt;
    }

    private int count(String table) {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return result == null ? 0 : result;
    }

    private static byte[] binary(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChapterAdaptationException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
