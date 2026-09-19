package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextFragment;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextPlan;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextRole;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationContextSnapshot;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionFence;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationContextService;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationComparisonEngine;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationComparisonService;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationText;
import com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationException;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationExecutionService;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadAuthorization;
import com.yuyutian.mytools.reader.model.adaptation.WorkloadResource;
import com.yuyutian.mytools.reader.controller.ReaderExceptionHandler;
import com.yuyutian.mytools.reader.controller.adaptation.AdaptationExecutionController;
import com.yuyutian.mytools.reader.controller.adaptation.ReaderAdaptationResponseFilter;
import com.yuyutian.mytools.reader.service.adaptation.authorization.ReaderWorkloadAuthorizer;
import com.yuyutian.mytools.reader.service.adaptation.authorization.ReaderWorkloadAuthority;
import com.yuyutian.mytools.reader.service.adaptation.authorization.WorkloadAuthorizationFixtures;
import org.flywaydb.core.Flyway;
import org.h2.api.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationAttemptModels.Kind;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationAttemptPayload;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationAttemptService;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationStoryEngine;
import com.yuyutian.mytools.reader.service.adaptation.StoryFixtures;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationStoryService;
import com.yuyutian.mytools.reader.controller.adaptation.AdaptationStoryController;
import com.yuyutian.mytools.reader.config.ReaderStoryConstraintProperties;
import com.yuyutian.mytools.reader.controller.adaptation.AdaptationAttemptController;
import com.yuyutian.mytools.reader.service.adaptation.authorization.AttemptSettlementSigner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实版本与封存事务；仅外部取文和 Scheduler 已授权 claim 由固定夹具提供。 */
class AdaptationContextRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Timestamp TIME = Timestamp.from(NOW);
    private static final String SHA = "a".repeat(64);
    private static final String ORIGINAL = "The traveler leaves at dawn.\nThe destination remains unchanged.";
    private static final String OUTPUT = "At the first light, the traveler leaves for the same destination.";
    private final UUID shelf = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID binding = UUID.randomUUID();
    private final List<UUID> chapters = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final Map<UUID, String> texts = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger reads = new AtomicInteger();
    private Consumer<UUID> afterRead = ignored -> { };
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ChapterAdaptationRepository versions;
    private AdaptationContextRepository contexts;
    private AdaptationContextService service;
    private AdaptationExecutionRepository executions;
    private AdaptationExecutionService executionService;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:adaptation_context_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        versions = new ChapterAdaptationRepository(jdbc, manager, mapper,
                new ReaderAdaptationProperties(true, true, "fixture", "fixture", "prompt-v1", "rules-v1", 2), clock);
        contexts = new AdaptationContextRepository(jdbc, mapper, manager, clock);
        service = new AdaptationContextService(contexts, (owner, book, chapter) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(owner).isEqualTo(41);
            assertThat(book).isEqualTo(shelf);
            reads.incrementAndGet();
            ShelfChapterModels.Content content = content(chapter);
            jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", content.sha256(), chapter.toString());
            afterRead.accept(chapter);
            return content;
        });
        executions = new AdaptationExecutionRepository(jdbc, contexts, manager, clock);
        executionService = new AdaptationExecutionService(executions, service);
        jdbc.update("INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at) VALUES (?, 41, ?, '{}', ?, ?)",
                shelf.toString(), shelf.toString(), TIME, TIME);
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 41, ?, 'Fixture', 'https://fixture.invalid', TRUE, 1, ?, ?)
                """, source.toString(), source.toString(), TIME, TIME);
        jdbc.update("INSERT INTO book_source_version VALUES (?, 1, '{}', ?, ?)", source.toString(), SHA, TIME);
        jdbc.update("""
                INSERT INTO shelf_book_content_binding (id, owner_id, shelf_book_id, binding_type, source_id, source_version, source_book_key,
                    binding_revision, bound_shelf_version, status, catalog_revision, catalog_sha256, created_at, updated_at)
                VALUES (?, 41, ?, 'SOURCE_RUNTIME', ?, 1, ?, 1, 1, 'ACTIVE', 1, ?, ?, ?)
                """, binding.toString(), shelf.toString(), source.toString(), SHA, SHA, TIME, TIME);
        texts.put(chapters.get(0), "Previous events leave the traveler ready.");
        texts.put(chapters.get(1), ORIGINAL);
        texts.put(chapters.get(2), "Next morning, the traveler reaches the unchanged destination.");
        for (int index = 0; index < chapters.size(); index++) {
            insertChapter(chapters.get(index), index);
        }
        jdbc.update("INSERT INTO novel_adaptation_provider_deployment VALUES (?, 'fixture', ?, 1, ?, TRUE, ?)",
                binary("fixture"), binary("Fixture-Model"), SHA, TIME);
        com.yuyutian.mytools.reader.service.adaptation.AdaptationConsentFixtures.installAndAccept(jdbc,
                "fixture", "fixture", "rights", SHA, clock);
    }

    @AfterEach
    void shutdown() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    /** 来源读取是固定夹具，队列、领取、范围守卫、封存规则、版本 HTTP 均使用真实实现。 */
    @Nested
    class SourceChecks {
        private AdaptationSourceCheckRepository checks;
        private com.yuyutian.mytools.reader.service.adaptation.AdaptationSourceCheckService checker;
        private UUID root;

        @BeforeEach
        void initializeChecks() {
            root = create(AdaptationRequestKind.INITIAL, null);
            service.prepare(execution(root));
            completeFixture(root);
            checks = new AdaptationSourceCheckRepository(jdbc, contexts,
                    new ReaderAdaptationProperties(true, true, "fixture", "fixture", "prompt-v1", "rules-v1", 2), mapper, manager, clock);
            checker = new com.yuyutian.mytools.reader.service.adaptation.AdaptationSourceCheckService(checks, (owner, book, chapter) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(owner).isEqualTo(41); assertThat(book).isEqualTo(shelf);
                reads.incrementAndGet(); var content = content(chapter);
                jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", content.sha256(), chapter.toString());
                afterRead.accept(chapter); return content;
            }, new com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties(true, true, "", 180, 3, 2),
                    new com.yuyutian.mytools.reader.config.ReaderChapterContentProperties(50000, 33554432, 2097152, 120000, 30));
            reads.set(0);
        }

        @Test
        void shouldVerifyCompleteWindowWithoutMutatingBusinessHistoryAndExpireProof() {
            var before = versions.detail(41, root);
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
            assertThat(checker.request(41, root).status()).isEqualTo("QUEUED");
            assertThat(checker.request(41, root).status()).isEqualTo("QUEUED");
            assertThat(reads.get()).isZero();
            assertThat(checker.processOne()).isTrue();
            var proof = checks.status(41, root);
            assertThat(proof.status()).isEqualTo("CURRENT");
            assertThat(proof.sourceSha256()).isEqualTo(AdaptationText.sha256(ORIGINAL));
            assertThat(proof.validUntil()).isEqualTo(NOW.plusSeconds(60));
            assertThat(reads.get()).isEqualTo(3);
            assertThat(checker.request(41, root)).isEqualTo(proof);
            assertThat(checker.processOne()).isFalse();
            assertThat(versions.detail(41, root)).isEqualTo(before);
            clock.now = NOW.plusSeconds(60);
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
            assertThat(checker.request(41, root).status()).isEqualTo("QUEUED");
        }

        @Test
        void shouldRejectTargetChangeEvenWhenCachedHashInitiallyMatched() {
            checks.request(41, root); texts.put(chapters.get(1), ORIGINAL + " A new ending.");
            checker.processOne();
            assertThat(checks.status(41, root).status()).isEqualTo("STALE");
            assertThat(versions.detail(41, root).result().content()).isEqualTo(OUTPUT);
        }

        @Test
        void shouldRejectNeighborChangeAndNotRewriteOriginalSnapshot() {
            checks.request(41, root); texts.put(chapters.get(2), "A conflicting next chapter.");
            checker.processOne();
            assertThat(checks.status(41, root).status()).isEqualTo("STALE");
            assertThat(checks.status(41, root).reasonCode()).isEqualTo("READER_035");
            assertThat(contexts.original(41, root).text()).isEqualTo(ORIGINAL);
        }

        @Test
        void shouldKeepUnavailableDistinctFromChangedAndStopFurtherReads() {
            checks.request(41, root);
            afterRead = ignored -> { throw new IllegalStateException("private upstream payload"); };
            checker.processOne();
            var result = checks.status(41, root);
            assertThat(result.status()).isEqualTo("UNKNOWN");
            assertThat(result.reasonCode()).isEqualTo("READER_046");
            assertThat(reads.get()).isEqualTo(1);
            assertThat(result.toString()).doesNotContain("private");
        }

        @Test
        void shouldStopAfterDeletionDuringReadAndRejectCrossOwnerBeforeNetwork() {
            expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> checks.request(42, root));
            checks.request(41, root);
            afterRead = ignored -> jdbc.update("UPDATE shelf_book_chapter SET adaptation_delete_epoch = 1 WHERE id = ?", chapters.get(1).toString());
            checker.processOne();
            assertThat(reads.get()).isEqualTo(1);
            expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> checks.status(41, root));
        }

        @Test
        void shouldRejectLateOldClaimAfterExpiryAndNewRequest() {
            checks.request(41, root);
            var old = checks.claim(30); var plan = checks.plan(old);
            clock.now = NOW.plusSeconds(30);
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
            checks.request(41, root); var next = checks.claim(30);
            assertThat(next.checkId()).isNotEqualTo(old.checkId());
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> checks.complete(old, plan, contents()));
            checks.fail(old, ErrorCode.CHAPTER_SOURCE_CHANGED);
            assertThat(checks.status(41, root).status()).isEqualTo("CHECKING");
            checks.complete(next, checks.plan(next), contents());
            assertThat(checks.status(41, root).status()).isEqualTo("CURRENT");
        }

        @Test
        void shouldRevokeCurrentOnCatalogChangeOrLaterContentObservation() {
            checks.request(41, root); checker.processOne();
            jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", SHA, chapters.get(2).toString());
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
            jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", AdaptationText.sha256(texts.get(chapters.get(2))), chapters.get(2).toString());
            jdbc.update("UPDATE shelf_book_chapter SET chapter_title = 'Changed title' WHERE id = ?", chapters.get(2).toString());
            assertThat(checks.status(41, root).status()).isEqualTo("STALE");
        }

        @Test
        void shouldRejectIncompleteTokensAndCorruptedSnapshotEvenWithinTtl() {
            checks.request(41, root); checker.processOne();
            jdbc.update("UPDATE novel_adaptation_source_check SET content_tokens_json = '{}' WHERE adaptation_id = ?", root.toString());
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
            checks.request(41, root); checker.processOne();
            jdbc.update("UPDATE novel_chapter_adaptation_context SET content_text = 'Tampered' WHERE adaptation_id = ? AND context_role = 'PREVIOUS_TAIL'", root.toString());
            assertThat(checks.status(41, root).status()).isEqualTo("UNKNOWN");
        }

        @Test
        void shouldShareFrozenRulesForOptimizedAndRegeneratedVersions() {
            for (var kind : List.of(AdaptationRequestKind.OPTIMIZE, AdaptationRequestKind.REGENERATE)) {
                UUID child = create(kind, root); service.prepare(execution(child)); completeFixture(child);
                checks.request(41, child); checker.processOne();
                assertThat(checks.status(41, child).status()).isEqualTo("CURRENT");
            }
            assertThat(versions.history(41, shelf, chapters.get(1), 20, null).items()).hasSize(3);
        }

        @Test
        void shouldMergeConcurrentRequestsAndOnlyClaimOnce() throws Exception {
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return checks.request(41, root); });
                var second = pool.submit(() -> { start.await(); return checks.request(41, root); });
                start.countDown(); assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            }
            assertThat(checks.claim(30)).isNotNull(); assertThat(checks.claim(30)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_adaptation_source_check", Integer.class)).isEqualTo(1);
            new TransactionTemplate(manager).executeWithoutResult(ignored -> expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> checker.processOne()));
        }

        @Test
        void shouldUseMicrosecondLeaseAndRejectUnselectedVersions() {
            clock.now = NOW.plusNanos(123456789); checks.request(41, root);
            var claim = checks.claim(30); assertThat(claim.deadline().getNano()).isEqualTo(123456000);
            assertThat(checks.plan(claim)).isNotNull();
            UUID child = create(AdaptationRequestKind.REGENERATE, root);
            expect(ErrorCode.ADAPTATION_PARENT_INVALID, () -> checks.request(41, child));
        }

        @Test
        void shouldExposeAsyncCheckAndCurrentDetailThroughRealReaderController() throws Exception {
            var application = new com.yuyutian.mytools.reader.service.adaptation.ChapterAdaptationService(versions,
                    new com.yuyutian.mytools.reader.service.adaptation.SourceLocatorCipher("fixture", Map.of("fixture", new byte[32])), checker, clock);
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new com.yuyutian.mytools.reader.controller.adaptation.ChapterAdaptationController(application, mapper))
                    .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
            String route = "/api/v1/reader-state/chapter-adaptations/" + root;
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route + "/source-check?ownerId=41").content("{}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(route + "/source-check?ownerId=41"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("QUEUED"));
            checker.processOne();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route + "?ownerId=41"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sourceRelation").value("CURRENT"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store, private"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(route + "/source-check?ownerId=42"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        }
    }

    @Test
    void shouldClaimFreezeAndAnalyzeThroughRealExecutionService() {
        var authorization = queued(create(AdaptationRequestKind.INITIAL, null));
        var claim = executionService.claim(authorization);
        assertThat(claim.nextAction()).isEqualTo("PREPARE_CONTEXT");
        assertThat(claim.context()).isNull();
        assertThat(claim.state().status()).isEqualTo(com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus.CONTEXT_FREEZING);
        assertThat(executionService.prepare(authorization).fragments()).hasSize(4);
        assertThat(reads.get()).isEqualTo(3);
        var ready = executionService.claim(authorization);
        assertThat(ready.nextAction()).isEqualTo("ANALYZE");
        assertThat(ready.context().manifestSha256()).hasSize(64);
        assertThat(ready.inputs().modelId()).isEqualTo("Fixture-Model");
        assertThat(ready.state().callsRemaining()).isEqualTo(5);
        long version = versions.progress(41, authorization.resourceId()).version();
        assertThat(executionService.claim(authorization)).isEqualTo(ready);
        assertThat(versions.progress(41, authorization.resourceId()).version()).isEqualTo(version);
        assertThat(reads.get()).isEqualTo(3);
    }

    @Test
    void shouldTakeOverOnlyWithHigherFenceAndPreserveBudgetAndContext() {
        var original = queued(create(AdaptationRequestKind.INITIAL, null));
        executionService.claim(original);
        var sealed = executionService.prepare(original);
        executionService.claim(original);
        jdbc.update("UPDATE novel_chapter_adaptation SET provider_call_budget_remaining = 2, provider_millis_remaining = 12345 WHERE id = ?",
                original.resourceId().toString());
        var replacement = authorization(original, UUID.randomUUID(), 2);
        var claim = executionService.claim(replacement);
        assertThat(claim.context()).isEqualTo(sealed);
        assertThat(claim.state().callsRemaining()).isEqualTo(2);
        assertThat(claim.state().providerMillisRemaining()).isEqualTo(12345);
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.claim(original));
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.prepare(original));
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.status(original));
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.claim(authorization(original, UUID.randomUUID(), 2)));
    }

    @Test
    void shouldRejectClaimBeforeTaskBindingAndFromWrongTask() {
        UUID id = create(AdaptationRequestKind.INITIAL, null);
        var request = new WorkloadAuthorization(WorkloadResource.CHAPTER_ADAPTATION, id, UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(60));
        expect(ErrorCode.ADAPTATION_TASK_BIND_PENDING, () -> executionService.claim(request));
        var valid = queued(id);
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.claim(request));
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.prepare(valid));
        assertThat(contextCount(id)).isZero();
    }

    @Test
    void shouldStopPreparationAfterCancellationAndRefuseExpiredOrDeletedExecution() {
        var request = queued(create(AdaptationRequestKind.INITIAL, null));
        executionService.claim(request);
        afterRead = ignored -> versions.cancel(41, request.resourceId());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.prepare(request));
        assertThat(reads.get()).isEqualTo(1);
        assertThat(contextCount(request.resourceId())).isZero();
        assertThat(executionService.status(request).stopRequested()).isTrue();
        clock.now = NOW.plusSeconds(60);
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> executionService.status(request));
        clock.now = NOW;
        jdbc.update("UPDATE shelf_book_chapter SET adaptation_delete_epoch = 1 WHERE id = ?", chapters.get(1).toString());
        expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> executionService.status(request));
    }

    @Test
    void shouldRejectCorruptSealWithoutAdvancingTheClaim() {
        var original = queued(create(AdaptationRequestKind.INITIAL, null));
        executionService.claim(original);
        executionService.prepare(original);
        jdbc.update("UPDATE novel_chapter_adaptation SET context_manifest_sha256 = ? WHERE id = ?", SHA, original.resourceId().toString());
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> executionService.claim(authorization(original, UUID.randomUUID(), 2)));
        assertThat(jdbc.queryForObject("SELECT fencing_token FROM novel_chapter_adaptation WHERE id = ?", Long.class, original.resourceId().toString())).isEqualTo(1);
        assertThat(versions.progress(41, original.resourceId()).status()).isEqualTo(com.yuyutian.mytools.reader.model.adaptation.AdaptationStatus.CONTEXT_FREEZING);
    }

    @Test
    void shouldRollBackClaimOnDatabaseWriteFailure() {
        var request = queued(create(AdaptationRequestKind.INITIAL, null));
        jdbc.execute("CREATE TRIGGER reject_claim BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '"
                + FailExecutionClaimTrigger.class.getName() + "'");
        assertThatThrownBy(() -> executionService.claim(request)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForMap("SELECT current_execution_id, fencing_token, status FROM novel_chapter_adaptation WHERE id = ?", request.resourceId().toString()))
                .containsEntry("current_execution_id", null).containsEntry("fencing_token", null).containsEntry("status", "QUEUED");
    }

    @Test
    void shouldReturnRecoveryActionInsteadOfResettingUnsettledCalls() {
        var original = queued(create(AdaptationRequestKind.INITIAL, null));
        executionService.claim(original);
        executionService.prepare(original);
        UUID pending = attempt(original.resourceId(), 1, "PLAN", null);
        jdbc.update("""
                UPDATE novel_chapter_adaptation_attempt SET status = 'SEND_STARTED', completed_at = NULL,
                    terminal_payload_sha256 = NULL, transmission_count = 1, last_send_started_at = ? WHERE id = ?
                """, TIME, pending.toString());
        var replacement = executionService.claim(authorization(original, UUID.randomUUID(), 2));
        assertThat(replacement.nextAction()).isEqualTo("RECOVER_ATTEMPTS");
        assertThat(replacement.state().pendingAttempts()).hasSize(1);
        assertThat(replacement.state().pendingAttempts().getFirst().status()).isEqualTo("SEND_STARTED");
    }

    @Test
    void shouldAuthorizeRealControllerBeforeClaimAndNeverAcceptOwnerOrText() throws Exception {
        var request = queued(create(AdaptationRequestKind.INITIAL, null));
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authority = org.mockito.Mockito.mock(ReaderWorkloadAuthority.class);
        org.mockito.Mockito.when(authority.publicKey("fixture")).thenReturn(key.getPublic());
        org.mockito.Mockito.when(authority.activeUntil(org.mockito.ArgumentMatchers.any())).thenReturn(NOW.plusSeconds(60));
        var authorizer = new ReaderWorkloadAuthorizer(WorkloadAuthorizationFixtures.properties(), authority, mapper, clock);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdaptationExecutionController(authorizer, executionService))
                .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
        String path = "/api/internal/v1/chapter-adaptations/" + request.resourceId() + "/executions/" + request.executionId();
        var claims = WorkloadAuthorizationFixtures.claims(WorkloadResource.CHAPTER_ADAPTATION, request.resourceId(), request.taskInstanceId(), request.executionId(), 1, NOW);
        String token = WorkloadAuthorizationFixtures.token(key, claims);
        var certificate = new java.security.cert.X509Certificate[]{WorkloadAuthorizationFixtures.certificate()};
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/claim").header("Authorization", "Bearer fixture-service-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store, private"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/claim").secure(true)
                        .requestAttr("jakarta.servlet.request.X509Certificate", certificate).header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.nextAction").value("PREPARE_CONTEXT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.ownerId").doesNotExist());
        for (String suffix : List.of("/claim", "/prepare-context")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + suffix + "?ownerId=42").secure(true)
                            .requestAttr("jakarta.servlet.request.X509Certificate", certificate).header("Authorization", "Bearer " + token))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + suffix).secure(true)
                            .requestAttr("jakarta.servlet.request.X509Certificate", certificate).header("Authorization", "Bearer " + token).content("{\"text\":\"not allowed\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        assertThat(reads.get()).isZero();
        org.mockito.Mockito.when(authority.activeUntil(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path + "/prepare-context").secure(true)
                        .requestAttr("jakarta.servlet.request.X509Certificate", certificate).header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("READER_059"));
        assertThat(reads.get()).isZero();
    }

    private WorkloadAuthorization queued(UUID adaptationId) {
        UUID task = UUID.randomUUID();
        // 仅模拟 Scheduler 已创建任务后的回绑，执行身份由新 claim 事务写入。
        jdbc.update("UPDATE novel_chapter_adaptation SET task_instance_id = ?, status = 'QUEUED', current_stage = 'QUEUED' WHERE id = ?",
                task.toString(), adaptationId.toString());
        return new WorkloadAuthorization(WorkloadResource.CHAPTER_ADAPTATION, adaptationId, task, UUID.randomUUID(), 1, NOW.plusSeconds(60));
    }

    private WorkloadAuthorization authorization(WorkloadAuthorization original, UUID executionId, long fence) {
        return new WorkloadAuthorization(original.resource(), original.resourceId(), original.taskInstanceId(), executionId, fence, original.authorizedUntil());
    }

    /** 测试领取写入失败时不得遗留部分身份。 */
    public static final class FailExecutionClaimTrigger implements Trigger {
        /** 在数据库写入点注入故障。 */
        @Override public void fire(Connection connection, Object[] before, Object[] after) throws SQLException {
            throw new SQLException("Fixture claim write rejected", "45000");
        }
    }

    @Test
    void shouldFenceContextAfterPersistentDispatchAbort() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        jdbc.update("UPDATE novel_chapter_adaptation SET dispatch_abort_error_code = ? WHERE id = ?",
                ErrorCode.ADAPTATION_DISPATCH_FAILED.code(), execution.adaptationId().toString());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> service.prepare(execution));
        assertThat(reads.get()).isZero();
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldFreezeAllRolesAtomicallyAndReadWithoutExternalSourceAfterSeal() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var snapshot = service.prepare(execution);
        assertThat(snapshot.fragments()).extracting(AdaptationContextFragment::role).containsExactlyInAnyOrder(
                AdaptationContextRole.TARGET_ORIGINAL, AdaptationContextRole.PREVIOUS_TAIL,
                AdaptationContextRole.NEXT_HEAD, AdaptationContextRole.CATALOG_METADATA);
        assertThat(snapshot.originalContentSha256()).isEqualTo(AdaptationText.sha256(ORIGINAL));
        assertThat(snapshot.baseContentSha256()).isEqualTo(snapshot.originalContentSha256());
        assertThat(contextCount(execution.adaptationId())).isEqualTo(4);
        assertThat(reads.get()).isEqualTo(3);
        assertThat(versions.progress(41, execution.adaptationId()).currentStage()).isEqualTo("CONTEXT_FROZEN");
        jdbc.update("UPDATE shelf_book_content_binding SET status = 'STALE' WHERE id = ?", binding.toString());
        texts.clear();
        assertThat(service.prepare(execution)).isEqualTo(snapshot);
        assertThat(contexts.frozen(execution)).isEqualTo(snapshot);
        assertThat(reads.get()).isEqualTo(3);
        assertThat(contexts.original(41, execution.adaptationId()).text()).isEqualTo(ORIGINAL);
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> contexts.original(42, execution.adaptationId()));
    }

    @Test
    void shouldUseRealBookBoundaryAndNeverMissingContentAsBoundary() {
        jdbc.update("UPDATE shelf_book_chapter SET active = FALSE WHERE id <> ?", chapters.get(1).toString());
        jdbc.update("UPDATE shelf_book_chapter SET chapter_index = 0 WHERE id = ?", chapters.get(1).toString());
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var snapshot = service.prepare(execution);
        assertThat(snapshot.identity().catalogChapterCount()).isEqualTo(1);
        assertThat(snapshot.fragments()).extracting(AdaptationContextFragment::role).containsExactlyInAnyOrder(
                AdaptationContextRole.TARGET_ORIGINAL, AdaptationContextRole.BOOK_START_MARKER,
                AdaptationContextRole.BOOK_END_MARKER, AdaptationContextRole.CATALOG_METADATA);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void shouldClipOnlyNeighborExcerptAtCodepointBoundaries() {
        String symbol = new String(Character.toChars(0x1f680));
        texts.put(chapters.get(0), "prefix" + symbol.repeat(4001));
        texts.put(chapters.get(2), symbol.repeat(4001) + "suffix");
        var snapshot = service.prepare(execution(create(AdaptationRequestKind.INITIAL, null)));
        assertThat(fragment(snapshot, AdaptationContextRole.PREVIOUS_TAIL).text()).isEqualTo(symbol.repeat(4000));
        assertThat(fragment(snapshot, AdaptationContextRole.NEXT_HEAD).text()).isEqualTo(symbol.repeat(4000));
        assertThat(fragment(snapshot, AdaptationContextRole.TARGET_ORIGINAL).text()).isEqualTo(ORIGINAL);
    }

    @Test
    void shouldLeaveNoPartialContextWhenNeighborReadFails() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        afterRead = chapter -> {
            if (chapter.equals(chapters.get(0))) {
                throw new ChapterAdaptationException(ErrorCode.RUNTIME_UNAVAILABLE);
            }
        };
        expect(ErrorCode.RUNTIME_UNAVAILABLE, () -> service.prepare(execution));
        assertThat(contextCount(execution.adaptationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT context_status FROM novel_chapter_adaptation WHERE id = ?",
                String.class, execution.adaptationId().toString())).isEqualTo("BUILDING");
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.frozen(execution));
    }

    @Test
    void shouldRejectCatalogRevisionChangeAfterLastNetworkRead() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        afterRead = chapter -> {
            if (chapter.equals(chapters.get(2))) {
                jdbc.update("UPDATE shelf_book_content_binding SET catalog_revision = 2 WHERE id = ?", binding.toString());
                jdbc.update("UPDATE shelf_book_chapter SET catalog_revision = 2 WHERE shelf_book_id = ?", shelf.toString());
            }
        };
        expect(ErrorCode.CHAPTER_CATALOG_STALE, () -> service.prepare(execution));
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldRejectChangedTargetHashAtFinalSeal() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        afterRead = chapter -> {
            if (chapter.equals(chapters.get(2))) {
                jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", SHA, chapters.get(1).toString());
            }
        };
        expect(ErrorCode.CHAPTER_SOURCE_CHANGED, () -> service.prepare(execution));
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldStopFurtherReadsAfterCancellation() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        afterRead = ignored -> versions.cancel(41, execution.adaptationId());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> service.prepare(execution));
        assertThat(reads.get()).isEqualTo(1);
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldRejectOldExecutionAndAllowOnlyNewFenceToSeal() {
        UUID id = create(AdaptationRequestKind.INITIAL, null);
        var old = execution(id);
        AdaptationContextPlan planned = contexts.plan(old);
        UUID nextExecution = UUID.randomUUID();
        jdbc.update("UPDATE novel_chapter_adaptation SET current_execution_id = ?, fencing_token = 2 WHERE id = ?", nextExecution.toString(), id.toString());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.seal(old, planned, contents()));
        var current = new AdaptationExecutionFence(id, old.taskInstanceId(), nextExecution, 2, 0, NOW.plusSeconds(300));
        assertThat(service.prepare(current).identity().adaptationId()).isEqualTo(id);
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.frozen(old));
    }

    @Test
    void shouldCheckAuthorizationExpiryDeadlineAndDeleteEpoch() {
        UUID id = create(AdaptationRequestKind.INITIAL, null);
        var execution = execution(id);
        var expired = new AdaptationExecutionFence(id, execution.taskInstanceId(), execution.executionId(), 1, 0, NOW);
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.plan(expired));
        jdbc.update("UPDATE novel_chapter_adaptation SET deadline_at = ? WHERE id = ?", TIME, id.toString());
        expect(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED, () -> contexts.plan(execution));
        jdbc.update("UPDATE novel_chapter_adaptation SET deadline_at = ? WHERE id = ?", Timestamp.from(NOW.plusSeconds(840)), id.toString());
        jdbc.update("UPDATE shelf_book_chapter SET adaptation_delete_epoch = 1 WHERE id = ?", chapters.get(1).toString());
        expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> contexts.plan(execution));
    }

    @Test
    void shouldRejectExpiryWhileFinalReadIsInFlight() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        afterRead = chapter -> {
            if (chapter.equals(chapters.get(2))) {
                clock.now = NOW.plusSeconds(300);
            }
        };
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> service.prepare(execution));
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldRequireClaimAndTaskBindingBeforePreparing() {
        UUID id = create(AdaptationRequestKind.INITIAL, null);
        var grant = new AdaptationExecutionFence(id, UUID.randomUUID(), UUID.randomUUID(), 1, 0, NOW.plusSeconds(300));
        expect(ErrorCode.ADAPTATION_TASK_BIND_PENDING, () -> contexts.plan(grant));
        jdbc.update("UPDATE novel_chapter_adaptation SET task_instance_id = ? WHERE id = ?", grant.taskInstanceId().toString(), id.toString());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.plan(grant));
        var claimed = execution(id);
        jdbc.update("UPDATE novel_chapter_adaptation SET status = 'QUEUED' WHERE id = ?", id.toString());
        expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.plan(claimed));
        assertThat(reads.get()).isZero();
    }

    @Test
    void shouldRejectMissingDuplicatedAndMixedRevisionEvidence() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.seal(execution, plan, List.of(content(chapters.get(1)))));
        var duplicate = List.of(content(chapters.get(1)), content(chapters.get(0)), content(chapters.get(0)));
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.seal(execution, plan, duplicate));
        var mixed = new ArrayList<>(contents());
        var first = mixed.getFirst();
        mixed.set(0, new ShelfChapterModels.Content(first.chapterId(), 2, 1, first.text(), first.sha256(), first.codepointCount()));
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.seal(execution, plan, mixed));
        assertThat(contextCount(execution.adaptationId())).isZero();
    }

    @Test
    void shouldRejectForgedMetadataAndUnrecordedSourceText() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        var changed = new AdaptationContextPlan(plan.identity(), plan.kind(), "Forged book rules.", plan.expectedSourceSha256(), null, null, null);
        expect(ErrorCode.CHAPTER_CATALOG_STALE, () -> contexts.seal(execution, changed, contents()));
        var altered = new ArrayList<>(contents());
        String text = "An unrecorded replacement for the previous chapter.";
        altered.set(0, new ShelfChapterModels.Content(chapters.get(0), 1, 1, text, AdaptationText.sha256(text), text.length()));
        expect(ErrorCode.CHAPTER_SOURCE_CHANGED, () -> contexts.seal(execution, plan, altered));
    }

    @Test
    void shouldReplayExactlySameSealButRejectDifferentPayload() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        var snapshot = contexts.seal(execution, plan, contents());
        assertThat(contexts.seal(execution, plan, contents())).isEqualTo(snapshot);
        var changed = new AdaptationContextPlan(plan.identity(), plan.kind(), "Different metadata.", plan.expectedSourceSha256(), null, null, null);
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.seal(execution, changed, contents()));
        assertThat(contextCount(execution.adaptationId())).isEqualTo(4);
    }

    @Test
    void shouldRejectCorruptedStoredTextAndManifest() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        service.prepare(execution);
        jdbc.update("UPDATE novel_chapter_adaptation_context SET content_text = 'Corrupted' WHERE adaptation_id = ? AND context_role = 'NEXT_HEAD'",
                execution.adaptationId().toString());
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.frozen(execution));
        jdbc.update("UPDATE novel_chapter_adaptation_context SET content_text = ? WHERE adaptation_id = ? AND context_role = 'NEXT_HEAD'",
                texts.get(chapters.get(2)), execution.adaptationId().toString());
        jdbc.update("UPDATE novel_chapter_adaptation SET context_manifest_sha256 = ? WHERE id = ?", SHA, execution.adaptationId().toString());
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.original(41, execution.adaptationId()));
    }

    @Test
    void shouldFreezeParentOnlyForOptimizeAndKeepRootForRegenerate() {
        var rootExecution = execution(create(AdaptationRequestKind.INITIAL, null));
        AdaptationContextSnapshot root = service.prepare(rootExecution);
        completeFixture(rootExecution.adaptationId());
        var optimizeExecution = execution(create(AdaptationRequestKind.OPTIMIZE, rootExecution.adaptationId()));
        var optimized = service.prepare(optimizeExecution);
        assertThat(optimized.fragments()).hasSize(5);
        assertThat(fragment(optimized, AdaptationContextRole.BASE_INPUT).text()).isEqualTo(OUTPUT);
        for (AdaptationContextFragment original : root.fragments()) {
            assertThat(fragment(optimized, original.role())).isEqualTo(original);
        }
        completeFixture(optimizeExecution.adaptationId());
        var regenerateExecution = execution(create(AdaptationRequestKind.REGENERATE, optimizeExecution.adaptationId()));
        var regenerated = service.prepare(regenerateExecution);
        assertThat(regenerated.fragments()).containsExactlyElementsOf(root.fragments());
        assertThat(regenerated.originalContentSha256()).isEqualTo(root.originalContentSha256());
        assertThat(regenerated.baseContentSha256()).isEqualTo(root.originalContentSha256());
        assertThat(contextCount(rootExecution.adaptationId())).isEqualTo(4);
    }

    @Test
    void shouldRejectDerivedContextIfOriginalNeighborChanged() {
        var root = execution(create(AdaptationRequestKind.INITIAL, null));
        service.prepare(root);
        completeFixture(root.adaptationId());
        var optimized = execution(create(AdaptationRequestKind.OPTIMIZE, root.adaptationId()));
        texts.put(chapters.get(2), "The destination has changed in the next chapter.");
        expect(ErrorCode.CHAPTER_SOURCE_CHANGED, () -> service.prepare(optimized));
        assertThat(contextCount(optimized.adaptationId())).isZero();
    }

    @Test
    void shouldRejectHalfContextResidueAndCatalogGaps() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_context (id, adaptation_id, owner_id, shelf_book_id, chapter_id,
                    context_role, sequence_no, source_chapter_id, content_text, content_sha256, codepoint_count, created_at)
                VALUES (?, ?, 41, ?, ?, 'TARGET_ORIGINAL', 0, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), execution.adaptationId().toString(), shelf.toString(), chapters.get(1).toString(),
                chapters.get(1).toString(), ORIGINAL, AdaptationText.sha256(ORIGINAL), ORIGINAL.length(), TIME);
        expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> contexts.seal(execution, plan, contents()));
        jdbc.update("UPDATE shelf_book_chapter SET chapter_index = 4 WHERE id = ?", chapters.get(2).toString());
        expect(ErrorCode.CHAPTER_CATALOG_STALE, () -> contexts.plan(execution));
    }

    @Test
    void shouldRejectAmbientTransactionBeforeAnyRead() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        new TransactionTemplate(manager).executeWithoutResult(ignored -> {
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> service.prepare(execution));
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> contexts.plan(execution));
        });
        assertThat(reads.get()).isZero();
    }

    @Test
    void shouldSerializeConcurrentSealsIntoOneImmutableSnapshot() throws Exception {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return contexts.seal(execution, plan, contents());
            });
            var second = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return contexts.seal(execution, plan, contents());
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(contextCount(execution.adaptationId())).isEqualTo(4);
    }

    @Test
    void shouldRequireNonNullCandidateLengthInDatabaseVisibilityCheck() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        service.prepare(execution);
        UUID candidate = completeFixture(execution.adaptationId());
        assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_codepoint_count = NULL WHERE id = ?", candidate.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldCompareFrozenOriginalNotCurrentSourceAndEnforceOwner() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        service.prepare(execution);
        var comparison = new AdaptationComparisonService(versions, contexts, new AdaptationComparisonEngine(mapper, () -> 0));
        expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> comparison.compare(41, execution.adaptationId()));
        completeFixture(execution.adaptationId());
        jdbc.update("UPDATE shelf_book_chapter SET content_sha256 = ? WHERE id = ?", SHA, chapters.get(1).toString());
        texts.clear();
        var result = comparison.compare(41, execution.adaptationId());
        assertThat(result.originalSha256()).isEqualTo(AdaptationText.sha256(ORIGINAL));
        assertThat(result.resultSha256()).isEqualTo(AdaptationText.sha256(OUTPUT));
        assertThat(reads.get()).isEqualTo(3);
        expect(ErrorCode.ADAPTATION_NOT_FOUND, () -> comparison.compare(42, execution.adaptationId()));
    }

    @Test
    void shouldRollbackAllFragmentsIfHeaderCommitFails() {
        var execution = execution(create(AdaptationRequestKind.INITIAL, null));
        var plan = contexts.plan(execution);
        jdbc.execute("""
                CREATE TRIGGER fixture_reject_context_header BEFORE UPDATE ON novel_chapter_adaptation
                    FOR EACH ROW CALL 'com.yuyutian.mytools.reader.repository.adaptation.AdaptationContextRepositoryTest$RejectHeaderTrigger'
                """);
        assertThatThrownBy(() -> contexts.seal(execution, plan, contents())).isInstanceOf(DataAccessException.class);
        assertThat(contextCount(execution.adaptationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT context_status FROM novel_chapter_adaptation WHERE id = ?",
                String.class, execution.adaptationId().toString())).isEqualTo("BUILDING");
    }

    /** 只存在于隔离测试库的故障注入，模拟片段插入后封存头写入失败。 */
    public static final class RejectHeaderTrigger implements Trigger {
        /** 固定失败不包含任何小说内容，触发整个短事务回滚。 */
        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("Fixture context header rejection", "45000");
        }
    }

    private UUID create(AdaptationRequestKind kind, UUID trigger) {
        return versions.create(new AdaptationCommand(41, shelf, chapters.get(1), kind, trigger, UUID.randomUUID().toString(),
                "Keep all original choices and outcomes.", 1, 1, AdaptationText.sha256(ORIGINAL))).adaptationId();
    }

    private AdaptationExecutionFence execution(UUID adaptationId) {
        var execution = new AdaptationExecutionFence(adaptationId, UUID.randomUUID(), UUID.randomUUID(), 1, 0, NOW.plusSeconds(300));
        // Scheduler 断言授权尚未接入，此处只建立已 claim 的数据库前置状态，不提供公共伪造入口。
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET task_instance_id = ?, current_execution_id = ?, fencing_token = 1,
                    status = 'CONTEXT_FREEZING', current_stage = 'FETCH_TARGET', dispatch_attempt_count = 1 WHERE id = ?
                """, execution.taskInstanceId().toString(), execution.executionId().toString(), adaptationId.toString());
        return execution;
    }

    private ShelfChapterModels.Content content(UUID chapter) {
        String text = texts.get(chapter);
        return new ShelfChapterModels.Content(chapter, 1, 1, text, AdaptationText.sha256(text), text.codePointCount(0, text.length()));
    }

    private List<ShelfChapterModels.Content> contents() {
        return chapters.stream().map(this::content).toList();
    }

    private long contextCount(UUID adaptationId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_context WHERE adaptation_id = ?", Long.class, adaptationId.toString());
        return count == null ? 0 : count;
    }

    private void insertChapter(UUID chapter, int index) {
        UUID locator = UUID.randomUUID();
        String key = AdaptationText.sha256(chapter.toString());
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
                """, chapter.toString(), shelf.toString(), binding.toString(), index, key, locator.toString(),
                AdaptationText.sha256(texts.get(chapter)), TIME, TIME);
    }

    /** 复用真实书架、版本、领取及封存前置条件，不用 SQL 伪造待执行上下文。 */
    @Nested
    class AttemptLedger {
        private String certificate;
        private AdaptationAttemptRepository ledger;

        @BeforeEach
        void initializeLedger() throws Exception {
            certificate = WorkloadAuthorizationFixtures.thumbprint();
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            ledger = new AdaptationAttemptRepository(jdbc, executions,
                    new AttemptSettlementSigner("fixture", Map.of("fixture", key), clock), new AdaptationAttemptPayload(mapper), manager, clock);
            // 仅夹具的 Provider 桶已通过人工契约登记，生产迁移不自动设为健康。
            insertBucket("AUTH", "1");
            insertBucket("AVAILABILITY", "Fixture-Model");
        }

        @Test
        void shouldReserveOnceAndFreezeTrustedConfigurationWithoutInventingSchedulerAttempt() {
            var auth = ready();
            var command = command(Kind.PLAN);
            var reserved = ledger.reserve(auth, command);
            assertThat(ledger.reserve(auth, command)).isEqualTo(reserved);
            assertThat(reserved.attemptNo()).isEqualTo(1);
            assertThat(reserved.reservedMillis()).isEqualTo(90000);
            var row = attemptRow(command);
            assertThat(row.get("settlement_token_sha256")).isNull();
            assertThat(row.get("settlement_expires_at")).isNull();
            assertThat(row.get("scheduler_attempt_no")).isNull();
            assertThat(row.get("provider_deployment_id")).isEqualTo(binary("fixture"));
            assertThat(row.get("model_id")).isEqualTo(binary("Fixture-Model"));
            assertThat(executionService.status(auth).callsRemaining()).isEqualTo(4);
            assertThat(executionService.status(auth).providerMillisRemaining()).isEqualTo(570000);
            expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> ledger.reserve(auth,
                    new AdaptationAttemptModels.Reserve(command.providerAttemptId(), Kind.PLAN, "b".repeat(64))));
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.reserve(auth, command(Kind.PLAN)));
        }

        @Test
        void shouldSerializeConcurrentReservation() throws Exception {
            var auth = ready();
            var command = command(Kind.PLAN);
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                var first = pool.submit(() -> { start.await(); return ledger.reserve(auth, command); });
                var second = pool.submit(() -> { start.await(); return ledger.reserve(auth, command); });
                start.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
                assertThat(executionService.status(auth).callsRemaining()).isEqualTo(4);
            } finally { pool.shutdownNow(); }
        }

        @Test
        void shouldRejectStageBypassMissingCertificateAndExhaustedBudgets() {
            var auth = ready();
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.reserve(auth, command(Kind.GENERATE)));
            var missing = new WorkloadAuthorization(auth.resource(), auth.resourceId(), auth.taskInstanceId(), auth.executionId(), auth.fencingToken(), auth.authorizedUntil());
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.reserve(missing, command(Kind.PLAN)));
            jdbc.update("UPDATE novel_chapter_adaptation SET provider_call_budget_remaining = 0 WHERE id = ?", auth.resourceId().toString());
            expect(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED, () -> ledger.reserve(auth, command(Kind.PLAN)));
            jdbc.update("UPDATE novel_chapter_adaptation SET provider_call_budget_remaining = 5, provider_millis_remaining = 0 WHERE id = ?", auth.resourceId().toString());
            expect(ErrorCode.ADAPTATION_DEADLINE_EXCEEDED, () -> ledger.reserve(auth, command(Kind.PLAN)));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt", Integer.class)).isZero();
        }

        @Test
        void shouldRollBackReservationWhenParentBudgetCommitFails() {
            var auth = ready();
            jdbc.execute("CREATE TRIGGER fail_attempt_budget BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '"
                    + FailExecutionClaimTrigger.class.getName() + "'");
            expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> ledger.reserve(auth, command(Kind.PLAN)));
            jdbc.execute("DROP TRIGGER fail_attempt_budget");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt_reservation", Integer.class)).isZero();
            assertThat(executionService.status(auth).callsRemaining()).isEqualTo(5);
        }

        @Test
        void shouldRequireLiveConsentAndClosedRegisteredCircuitsBeforeSending() {
            var auth = ready();
            // 显式构造升级前的任务，保留历史授权撤销测试。
            jdbc.update("UPDATE novel_chapter_adaptation SET consent_policy = 'EXPLICIT', disclosure_version = ?, consent_revision = 1 WHERE id = ?",
                    "fixture".getBytes(StandardCharsets.UTF_8), auth.resourceId().toString());
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = ? WHERE owner_id = 41", TIME);
            expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = NULL WHERE owner_id = 41");
            jdbc.update("UPDATE reader_adaptation_provider_disclosure SET enabled = FALSE");
            expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            jdbc.update("UPDATE reader_adaptation_provider_disclosure SET enabled = TRUE");
            jdbc.update("UPDATE novel_adaptation_provider_circuit SET state = 'OPEN' WHERE bucket_kind = 'AUTH'");
            expect(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            jdbc.update("DELETE FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AUTH'");
            expect(ErrorCode.ADAPTATION_PROVIDER_UNAVAILABLE, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            assertThat(attemptRow(command).get("status")).isEqualTo("REGISTERED");
            assertThat(attemptRow(command).get("transmission_count")).isEqualTo(0);
            assertThat(attemptRow(command).get("settlement_token_sha256")).isNull();
            insertBucket("AUTH", "1");
            assertThat(ledger.sendStarted(auth, command.providerAttemptId()).maySend()).isTrue();
        }

        @Test
        void shouldSendDirectRequestsWithoutAnActiveConsent() {
            var auth = ready();
            jdbc.update("UPDATE reader_adaptation_provider_consent SET revoked_at = ? WHERE owner_id = 41", TIME);
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            assertThat(ledger.sendStarted(auth, command.providerAttemptId()).maySend()).isTrue();
        }

        @Test
        void shouldFencePreviouslyCreatedTaskAfterRealRevokeAndNewConsent() {
            var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
            jdbc.update("UPDATE novel_chapter_adaptation SET consent_policy = 'EXPLICIT', disclosure_version = ?, consent_revision = 1 WHERE id = ?",
                    "fixture".getBytes(StandardCharsets.UTF_8), auth.resourceId().toString());
            var consent = new AdaptationConsentRepository(jdbc,
                    new ReaderAdaptationProperties(true, false, "fixture", "fixture", "fixture", "fixture", 2), mapper, manager, clock);
            assertThat(consent.features(41).consentRevision()).isEqualTo(1);
            consent.revoke(41, 1);
            expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            var disclosure = consent.features(41).disclosure();
            consent.accept(41, new com.yuyutian.mytools.reader.model.adaptation.AdaptationConsentModels.Accept(
                    disclosure.version(), disclosure.disclosureSha256(), true, true, 2));
            expect(ErrorCode.ADAPTATION_PROVIDER_CONSENT_REQUIRED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            assertThat(attemptRow(command).get("transmission_count")).isEqualTo(0);
            assertThat(versions.progress(41, auth.resourceId()).status().name()).isEqualTo("ANALYZING");
        }

        @Test
        void shouldGrantOnlyOneSendAndRecreateOnlyTheSameSettlementReceipt() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var first = ledger.sendStarted(auth, command.providerAttemptId());
            var replay = ledger.sendStarted(auth, command.providerAttemptId());
            assertThat(first.maySend()).isTrue();
            assertThat(replay.maySend()).isFalse();
            assertThat(first.attemptSettlementToken()).isEqualTo(replay.attemptSettlementToken());
            assertThat(first.callDeadlineAt()).isEqualTo(NOW.plusSeconds(90));
            assertThat(first.settlementExpiresAt()).isEqualTo(NOW.plusSeconds(210));
            assertThat(attemptRow(command).get("settlement_token_sha256")).isEqualTo(AdaptationText.sha256(first.attemptSettlementToken()));
            assertThat(attemptRow(command).values()).doesNotContain(first.attemptSettlementToken());
            assertThat(first.toString()).doesNotContain(first.attemptSettlementToken());
            assertThat(executionService.status(auth).providerMillisRemaining()).isEqualTo(570000);
            assertThat(executionService.status(auth).retriesRemaining()).isEqualTo(2);
        }

        @Test
        void shouldSettleCanonicalResultOnceWithoutSelectingOrAdvancingBusinessState() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            var result = settle(auth, command, sent, auth, success(validPlan()));
            assertThat(result.archivedOnly()).isFalse();
            assertThat(settle(auth, command, sent, auth, success(" " + validPlan() + " "))).isEqualTo(result);
            expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> settle(auth, command, sent, auth, success(validPlan().replace("Add sensory detail", "Add subtle detail"))));
            assertThat(executionService.status(auth).status().name()).isEqualTo("ANALYZING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            assertThat(attemptRow(command).get("viewable_candidate")).isEqualTo(false);
        }

        @Test
        void shouldArchiveCancelledAndTakenOverResultsWithoutRestoringOldAuthority() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            var next = withCertificate(authorization(auth, UUID.randomUUID(), 2));
            executionService.claim(next);
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
            var result = settle(auth, command, sent, null, success(validPlan()));
            assertThat(result.archivedOnly()).isTrue();
            assertThat(executionService.status(next).callsRemaining()).isEqualTo(4);
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.reserve(next, command(Kind.GENERATE)));
            versions.cancel(41, auth.resourceId());
            assertThat(settle(auth, command, sent, null, success(validPlan()))).isEqualTo(result);
            assertThat(executionService.status(next).stopRequested()).isTrue();
        }

        @Test
        void shouldRejectUntrustedPlanHttpBeforeAnyTerminalSideEffectAndAllowExactValidSettlement() throws Exception {
            var http = http(); var auth = http.authorization(); var command = command(Kind.PLAN);
            ledger.reserve(auth, command); var sent = ledger.sendStarted(auth, command.providerAttemptId());
            var before = executionService.status(auth);
            String valid = validPlan();
            for (String invalid : List.of("{}", valid.replace("{", "{\"reasoning\":\"private-schema-sentinel\","),
                    valid.replace("SENSORY", "NEW_PLOT"), StoryFixtures.critic(ORIGINAL, SHA, null, false))) {
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(http.path() + "/attempts/" + command.providerAttemptId()))
                                .header("X-Reader-Attempt-Settlement", sent.attemptSettlementToken()).contentType("application/json")
                                .content(mapper.writeValueAsString(success(invalid))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(ErrorCode.ADAPTATION_REQUEST_INVALID.code()))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-schema-sentinel"))));
                assertThat(attemptRow(command)).containsEntry("status", "SEND_STARTED").containsEntry("plan_json", null)
                        .containsEntry("plan_sha256", null).containsEntry("terminal_payload_sha256", null);
                assertThat(executionService.status(auth)).isEqualTo(before);
            }
            assertThat(ledger.sendStarted(auth, command.providerAttemptId()).maySend()).isFalse();
            var accepted = settle(auth, command, sent, auth, success(valid));
            assertThat(accepted.status()).isEqualTo("SUCCEEDED");
            assertThat(settle(auth, command, sent, auth, success(valid))).isEqualTo(accepted);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            assertThat(executionService.status(auth).status().name()).isEqualTo("ANALYZING");
        }

        @Test
        void shouldEnforceSchemaForCancelledCertificateOnlyArchivalWithoutRestoringAuthority() {
            var auth = ready(); var command = command(Kind.PLAN);
            ledger.reserve(auth, command); var sent = ledger.sendStarted(auth, command.providerAttemptId());
            versions.cancel(41, auth.resourceId());
            expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> settle(auth, command, sent, null, success("{\"reasoning\":\"private-schema-sentinel\"}")));
            assertThat(attemptRow(command)).containsEntry("status", "SEND_STARTED").containsEntry("plan_json", null);
            assertThat(settle(auth, command, sent, null, success(validPlan())).archivedOnly()).isTrue();
            assertThat(executionService.status(auth).stopRequested()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
        }

        @Test
        void shouldArchiveFirstResultAfterCancellation() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            versions.cancel(41, auth.resourceId());
            assertThat(settle(auth, command, sent, null, success(validPlan()))).satisfies(result -> {
                assertThat(result.archivedOnly()).isTrue();
                assertThat(result.status()).isEqualTo("SUCCEEDED");
            });
            assertThat(executionService.status(auth).stopRequested()).isTrue();
        }

        @Test
        void shouldRejectChangedScopeCertificateExpiredCapabilityAndDeletedEpoch() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.settle(auth.resourceId(), auth.executionId(), command.providerAttemptId(),
                    "x".repeat(43), sent.attemptSettlementToken(), auth, success(validPlan())));
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.settle(auth.resourceId(), UUID.randomUUID(), command.providerAttemptId(),
                    certificate, sent.attemptSettlementToken(), auth, success(validPlan())));
            clock.now = sent.settlementExpiresAt();
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> settle(auth, command, sent, null, success(validPlan())));
            clock.now = NOW;
            jdbc.update("UPDATE shelf_book_chapter SET adaptation_delete_epoch = 1 WHERE id = ?", chapters.get(1).toString());
            expect(ErrorCode.ADAPTATION_HISTORY_DELETED, () -> settle(auth, command, sent, null, success(validPlan())));
            assertThat(attemptRow(command).get("status")).isEqualTo("SEND_STARTED");
        }

        @Test
        void shouldRecoverNeverSentReservationWithoutCallingProviderOrRefundingBudget() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            assertThat(ledger.recover(auth)).isZero();
            clock.now = NOW.plusSeconds(30);
            assertThat(ledger.recover(auth)).isEqualTo(1);
            assertThat(ledger.recover(auth)).isZero();
            assertThat(attemptRow(command).get("status")).isEqualTo("FAILED");
            assertThat(attemptRow(command).get("transmission_count")).isEqualTo(0);
            assertThat(executionService.status(auth).providerMillisRemaining()).isEqualTo(570000);
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
        }

        @Test
        void shouldPreserveSettlementWindowThenRecoverUnconfirmedSendAsUnknown() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            var next = withCertificate(authorization(auth, UUID.randomUUID(), 2));
            executionService.claim(next);
            assertThat(ledger.recover(next)).isZero();
            clock.now = sent.settlementExpiresAt();
            next = new WorkloadAuthorization(next.resource(), next.resourceId(), next.taskInstanceId(), next.executionId(), 2, clock.instant().plusSeconds(60), certificate);
            assertThat(ledger.recover(next)).isEqualTo(1);
            assertThat(attemptRow(command).get("status")).isEqualTo("CALL_OUTCOME_UNKNOWN");
            assertThat(attemptRow(command).get("error_code")).isEqualTo("READER_054");
            assertThat(executionService.status(next).callsRemaining()).isEqualTo(4);
            assertThat(executionService.status(next).retriesRemaining()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT failure_count FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AVAILABILITY'", Integer.class)).isEqualTo(1);
        }

        @Test
        void shouldOpenAuthCircuitForUnauthorizedButNotOrdinaryForbidden() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            var rejected = new AdaptationAttemptModels.Terminal("FAILED", null, null, "error", null, null, null, 403, "READER_055", SHA);
            settle(auth, command, sent, auth, rejected);
            assertThat(jdbc.queryForObject("SELECT state FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AUTH'", String.class)).isEqualTo("CLOSED");
            // 独立版本的鉴权失败应影响共享桶，但普通 403 不能改写已经结算的前一调用。
            jdbc.update("UPDATE novel_chapter_adaptation SET status = 'FAILED', finished_at = ? WHERE id = ?", TIME, auth.resourceId().toString());
            var second = ready();
            var secondCommand = command(Kind.PLAN);
            ledger.reserve(second, secondCommand);
            var secondSend = ledger.sendStarted(second, secondCommand.providerAttemptId());
            var unauthorized = new AdaptationAttemptModels.Terminal("FAILED", null, null, "error", null, null, null, 401, "READER_040", SHA);
            settle(second, secondCommand, secondSend, second, unauthorized);
            assertThat(jdbc.queryForObject("SELECT state FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AUTH'", String.class)).isEqualTo("OPEN");
        }

        private WorkloadAuthorization ready() {
            var auth = withCertificate(queued(create(AdaptationRequestKind.INITIAL, null)));
            executionService.claim(auth);
            executionService.prepare(auth);
            executionService.claim(auth);
            return auth;
        }

        /** 不提供当前执行授权，验证独立扫描、窄结算和派发取消屏障的真实事务协作。 */
        @Nested
        class BackgroundRecovery {
            @Test
            void shouldRecoverAfterRevocationAtExactReservationExpiryAndFenceContinuation() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var consent = new AdaptationConsentRepository(jdbc,
                        new ReaderAdaptationProperties(false, false, "fixture", "fixture", "fixture", "fixture", 2), mapper, manager, clock);
                consent.revoke(41, 1);
                clock.now = NOW.plusSeconds(30).minusNanos(1000);
                assertThat(ledger.expiredAdaptationIds()).isEmpty();
                assertThat(ledger.recoverExpired(auth.resourceId())).isZero();
                clock.now = NOW.plusSeconds(30);
                assertThat(ledger.expiredAdaptationIds()).containsExactly(auth.resourceId());
                assertThat(ledger.expiredAdaptationIds(auth.resourceId())).isEmpty();
                assertThat(ledger.expiredAdaptationIds(UUID.fromString("00000000-0000-0000-0000-000000000000"))).containsExactly(auth.resourceId());
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
                var row = attemptRow(command);
                assertThat(row.get("status")).isEqualTo("FAILED");
                assertThat(row.get("transmission_count")).isEqualTo(0);
                assertThat(row.get("archived_only")).isEqualTo(true);
                assertThat(row.get("terminal_payload_sha256")).isNotNull();
                assertThat(row.get("output_text")).isNull(); assertThat(row.get("plan_json")).isNull();
                assertThat(parent(auth).get("dispatch_abort_error_code")).isEqualTo(ErrorCode.ADAPTATION_EXECUTION_FENCED.code());
                assertThat(parent(auth).get("status")).isEqualTo("ANALYZING");
                assertThat(parent(auth).get("provider_call_budget_remaining")).isEqualTo(4);
                assertThat(((Number) parent(auth).get("provider_millis_remaining")).longValue()).isEqualTo(570000);
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.sendStarted(auth, command.providerAttemptId()));
                assertThat(ledger.recoverExpired(auth.resourceId())).isZero();
                assertThat(ledger.expiredAdaptationIds()).isEmpty();
            }

            @Test
            void shouldKeepSentWindowDespiteCancelThenRequireSchedulerBarrierForFinalState() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var sent = ledger.sendStarted(auth, command.providerAttemptId());
                versions.cancel(41, auth.resourceId());
                var dispatch = dispatch();
                var first = dispatch.claim("fixture-recovery");
                dispatch.acknowledge(first, view(auth, true, "CANCELLED"));
                assertThat(parent(auth).get("status")).isEqualTo("CANCEL_REQUESTED");
                clock.now = sent.settlementExpiresAt().minusNanos(1000);
                assertThat(ledger.expiredAdaptationIds()).isEmpty();
                assertThat(ledger.recoverExpired(auth.resourceId())).isZero();
                clock.now = sent.settlementExpiresAt();
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
                assertThat(attemptRow(command).get("status")).isEqualTo("CALL_OUTCOME_UNKNOWN");
                assertThat(attemptRow(command).get("transmission_count")).isEqualTo(1);
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> settle(auth, command, sent, null, success(validPlan())));
                var pending = dispatch.claim("fixture-recovery");
                dispatch.acknowledge(pending, view(auth, false, "CANCELLED"));
                assertThat(parent(auth).get("status")).isEqualTo("CANCEL_REQUESTED");
                clock.now = clock.now.plusSeconds(5);
                dispatch.acknowledge(dispatch.claim("fixture-recovery"), view(auth, true, "CANCELLED"));
                assertThat(parent(auth).get("status")).isEqualTo("CANCELLED");
                assertThat(parent(auth).get("last_error_code")).isNull();
                assertThat(jdbc.queryForObject("SELECT failure_count FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AVAILABILITY'", Integer.class)).isEqualTo(1);
            }

            @Test
            void shouldPreserveDispatchLeaseAndRejectLateRunningReplyAfterRecovery() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                clock.now = NOW.plusSeconds(29);
                var dispatch = dispatch(); var claim = dispatch.claim("fixture-recovery");
                clock.now = NOW.plusSeconds(30);
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
                assertThat(parent(auth).get("dispatch_claim_owner")).isEqualTo("fixture-recovery");
                assertThat(parent(auth).get("dispatch_epoch")).isEqualTo(claim.epoch());
                dispatch.acknowledge(claim, view(auth, false, "RUNNING"));
                assertThat(parent(auth).get("current_stage")).isEqualTo("DISPATCH_CANCELLING");
                assertThat(parent(auth).get("status")).isEqualTo("ANALYZING");
                clock.now = NOW.plusSeconds(35);
                var cancel = dispatch.claim("fixture-recovery");
                assertThat(cancel.action()).isEqualTo(com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.Action.CANCEL);
                dispatch.acknowledge(cancel, view(auth, true, "CANCELLED"));
                assertThat(parent(auth).get("status")).isEqualTo("FAILED");
                assertThat(parent(auth).get("last_error_code")).isEqualTo(ErrorCode.ADAPTATION_EXECUTION_FENCED.code());
            }

            @Test
            void shouldSettleTombstonedDeletedAndNewEpochScopeWithoutResurrectingContent() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var sent = ledger.sendStarted(auth, command.providerAttemptId());
                jdbc.update("UPDATE shelf_book SET deleted = TRUE WHERE id = ?", shelf.toString());
                jdbc.update("UPDATE shelf_book_chapter SET adaptation_delete_epoch = 1 WHERE id = ?", chapters.get(1).toString());
                jdbc.update("UPDATE novel_chapter_adaptation SET deletion_state = 'TOMBSTONED', tombstoned_at = ?, dispatch_abort_error_code = ? WHERE id = ?",
                        TIME, ErrorCode.CHAPTER_SOURCE_CHANGED.code(), auth.resourceId().toString());
                clock.now = sent.settlementExpiresAt();
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
                assertThat(parent(auth).get("deletion_state")).isEqualTo("TOMBSTONED");
                assertThat(parent(auth).get("dispatch_abort_error_code")).isEqualTo(ErrorCode.CHAPTER_SOURCE_CHANGED.code());
                assertThat(attemptRow(command).get("status")).isEqualTo("CALL_OUTCOME_UNKNOWN");
                assertThat(attemptRow(command).get("viewable_candidate")).isEqualTo(false);
                assertThat(attemptRow(command).get("output_text")).isNull();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            }

            @Test
            void shouldLeaveNarrowSettlementCommittedBeforeExpiryUntouched() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var sent = ledger.sendStarted(auth, command.providerAttemptId());
                clock.now = sent.settlementExpiresAt().minusNanos(1000);
                var terminal = settle(auth, command, sent, null, success(validPlan()));
                clock.now = sent.settlementExpiresAt();
                assertThat(ledger.expiredAdaptationIds()).isEmpty();
                assertThat(ledger.recoverExpired(auth.resourceId())).isZero();
                assertThat(attemptRow(command).get("terminal_payload_sha256")).isEqualTo(terminal.terminalPayloadSha256());
                assertThat(attemptRow(command).get("status")).isEqualTo("SUCCEEDED");
                assertThat(parent(auth).get("dispatch_abort_error_code")).isNull();
            }

            @Test
            void shouldSerializeConcurrentScannersWithoutDoubleCircuitPenalty() throws Exception {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var sent = ledger.sendStarted(auth, command.providerAttemptId()); clock.now = sent.settlementExpiresAt();
                var start = new CountDownLatch(1);
                try (var pool = Executors.newFixedThreadPool(2)) {
                    var first = pool.submit(() -> { start.await(); return ledger.recoverExpired(auth.resourceId()); });
                    var second = pool.submit(() -> { start.await(); return ledger.recoverExpired(auth.resourceId()); });
                    start.countDown();
                    assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                }
                assertThat(jdbc.queryForObject("SELECT failure_count FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AVAILABILITY'", Integer.class)).isEqualTo(1);
                assertThat(attemptRow(command).get("transmission_count")).isEqualTo(1);
            }

            @Test
            void shouldSkipLockedScopeAndRecoverAfterRelease() throws Exception {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command); clock.now = NOW.plusSeconds(30);
                try (var pool = Executors.newSingleThreadExecutor()) {
                    new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                        jdbc.queryForList("SELECT id FROM shelf_book WHERE id = ? FOR UPDATE", shelf.toString());
                        try { assertThat(pool.submit(() -> ledger.recoverExpired(auth.resourceId())).get(2, TimeUnit.SECONDS)).isZero(); }
                        catch (Exception exception) { throw new AssertionError(exception); }
                    });
                }
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
            }

            @Test
            void shouldRollBackTerminalAndCircuitWhenStopSignalWriteFails() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command);
                var sent = ledger.sendStarted(auth, command.providerAttemptId()); clock.now = sent.settlementExpiresAt();
                jdbc.execute("CREATE TRIGGER fail_recovery_parent BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '"
                        + FailExecutionClaimTrigger.class.getName() + "'");
                expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> ledger.recoverExpired(auth.resourceId()));
                jdbc.execute("DROP TRIGGER fail_recovery_parent");
                assertThat(attemptRow(command).get("status")).isEqualTo("SEND_STARTED");
                assertThat(attemptRow(command).get("terminal_payload_sha256")).isNull();
                assertThat(parent(auth).get("dispatch_abort_error_code")).isNull();
                assertThat(jdbc.queryForObject("SELECT failure_count FROM novel_adaptation_provider_circuit WHERE bucket_kind = 'AVAILABILITY'", Integer.class)).isZero();
                assertThat(ledger.recoverExpired(auth.resourceId())).isEqualTo(1);
            }

            @Test
            void shouldRejectAmbientTransactionsAndSkipMissingScope() {
                assertThat(ledger.recoverExpired(UUID.randomUUID())).isZero();
                expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> ledger.recoverExpired(null));
                new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                    expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, ledger::expiredAdaptationIds);
                    expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.recoverExpired(UUID.randomUUID()));
                });
            }

            @Test
            void shouldPersistStopSignalWhenCurrentExecutorRecoversBeforeExit() {
                var auth = ready(); var command = command(Kind.PLAN); ledger.reserve(auth, command); clock.now = NOW.plusSeconds(30);
                assertThat(ledger.recover(auth)).isEqualTo(1);
                assertThat(parent(auth).get("dispatch_abort_error_code")).isEqualTo(ErrorCode.ADAPTATION_EXECUTION_FENCED.code());
                assertThat(ledger.expiredAdaptationIds()).isEmpty();
                assertThat(dispatch().claim("fixture-recovery").action())
                        .isEqualTo(com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.Action.CANCEL);
            }

            @Test
            void shouldBoundAndPageDiscoveryWhilePreservingAlreadyTerminalBusinessHistory() {
                var ids = new ArrayList<UUID>();
                var authorizations = new ArrayList<WorkloadAuthorization>();
                for (int index = 0; index < 17; index++) {
                    var auth = ready(); ids.add(auth.resourceId()); authorizations.add(auth);
                    // 仅模拟旧服务遗留的已终态父行，不用批量扫描夹具证明正常业务完成。
                    jdbc.update("UPDATE novel_chapter_adaptation SET status = 'FAILED', finished_at = ?, last_error_code = ? WHERE id = ?",
                            TIME, ErrorCode.ADAPTATION_DISPATCH_FAILED.code(), auth.resourceId().toString());
                }
                // 新接口会阻止同章存在多个未决调用；批量旧数据仅以 SQL 夹具建立，不能削弱入口守卫。
                for (var auth : authorizations) {
                    String attemptId = UUID.randomUUID().toString();
                    jdbc.update("""
                            INSERT INTO novel_chapter_adaptation_attempt (id, adaptation_id, owner_id, attempt_no, call_kind,
                                task_instance_id, execution_id, fencing_token, provider_deployment_id, model_id, credential_generation,
                                provider_attempt_id, request_sha256, status, chapter_delete_epoch, created_at)
                            VALUES (?, ?, 41, 1, 'PLAN', ?, ?, 1, ?, ?, 1, ?, ?, 'REGISTERED', 0, ?)
                            """, attemptId, auth.resourceId().toString(), auth.taskInstanceId().toString(), auth.executionId().toString(),
                            binary("fixture"), binary("Fixture-Model"), UUID.randomUUID().toString(), SHA, TIME);
                    jdbc.update("""
                            INSERT INTO novel_chapter_adaptation_attempt_reservation (attempt_id, adaptation_id, owner_id,
                                executor_thumbprint, reservation_sha256, reserved_millis, reservation_expires_at, created_at)
                            VALUES (?, ?, 41, ?, ?, 90000, ?, ?)
                            """, attemptId, auth.resourceId().toString(), certificate, SHA, Timestamp.from(NOW.plusSeconds(30)), TIME);
                    jdbc.update("UPDATE novel_chapter_adaptation SET attempt_count = 1, provider_call_budget_remaining = 4, provider_millis_remaining = 570000 WHERE id = ?",
                            auth.resourceId().toString());
                }
                ids.sort(java.util.Comparator.comparing(UUID::toString));
                clock.now = NOW.plusSeconds(30);
                assertThat(ledger.expiredAdaptationIds()).containsExactlyElementsOf(ids.subList(0, 16));
                assertThat(ledger.expiredAdaptationIds(ids.get(15))).containsExactly(ids.get(16));
                assertThat(ledger.recoverExpired(ids.get(0))).isEqualTo(1);
                var row = jdbc.queryForMap("SELECT status, last_error_code, finished_at, dispatch_abort_error_code FROM novel_chapter_adaptation WHERE id = ?", ids.get(0).toString());
                assertThat(row.get("status")).isEqualTo("FAILED");
                assertThat(row.get("last_error_code")).isEqualTo(ErrorCode.ADAPTATION_DISPATCH_FAILED.code());
                assertThat(row.get("finished_at")).isEqualTo(TIME);
                assertThat(row.get("dispatch_abort_error_code")).isNull();
                assertThatThrownBy(() -> jdbc.update("UPDATE novel_chapter_adaptation SET dispatch_abort_error_code = ? WHERE id = ?",
                        "READER_999", ids.get(0).toString())).isInstanceOf(DataIntegrityViolationException.class);
            }

            private Map<String, Object> parent(WorkloadAuthorization auth) {
                return jdbc.queryForMap("SELECT * FROM novel_chapter_adaptation WHERE id = ?", auth.resourceId().toString());
            }
            private AdaptationDispatchRepository dispatch() {
                return new AdaptationDispatchRepository(jdbc, manager,
                        new com.yuyutian.mytools.reader.config.ReaderAdaptationDispatchProperties(true, 30, 5, 8, 5), clock);
            }
            private com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView view(
                    WorkloadAuthorization auth, boolean barrier, String status) {
                return new com.yuyutian.mytools.reader.model.adaptation.AdaptationDispatchModels.SchedulerView(auth.resourceId(), auth.taskInstanceId(), barrier, status);
            }
        }

        /** 整条 Reader 事务链不再用 SQL 伪造约束、进度、validation 或 selection，模型输出仍是固定夹具。 */
        @Nested
        class StoryWorkflow {
            private static final String ADAPTED = "In the pale light, the traveler leaves at dawn.\nThe destination remains unchanged.";
            private AdaptationStoryRepository stories;

            @BeforeEach
            void initializeWorkflow() {
                versions = new ChapterAdaptationRepository(jdbc, manager, mapper,
                        new ReaderAdaptationProperties(true, true, "fixture", "fixture", "prompt-v1", AdaptationStoryEngine.VERSION, 2), clock);
                stories = new AdaptationStoryRepository(jdbc, executions, contexts,
                        new AdaptationStoryEngine(mapper, new ReaderStoryConstraintProperties(700, 2500)), new AdaptationAttemptPayload(mapper), mapper, manager, clock);
            }

            @Test
            void shouldExecuteAndRestoreFullRewriteVersionAcrossEveryStoryBoundary() {
                var auth = ready();
                jdbc.update("UPDATE novel_chapter_adaptation SET constraint_version = ? WHERE id = ?",
                        AdaptationStoryEngine.REWRITE_VERSION, auth.resourceId().toString());
                assertThat(stories.workflow(auth).attempts()).isEmpty();
                var rules = analyze(auth);
                assertThat(stories.workflow(auth).constraints()).isEqualTo(rules);
                String rewritten = "At daybreak, the traveler sets out. The same destination awaits.";
                var candidate = generate(auth, rewritten, false);
                var review = review(auth, candidate, rewritten, rules, null);
                assertThat(review.outcome()).isEqualTo("PASS");
                assertThat(jdbc.queryForObject("SELECT deterministic_version FROM novel_chapter_adaptation_validation WHERE id = ?",
                        String.class, review.validationId().toString())).isEqualTo(AdaptationStoryEngine.REWRITE_VERSION);
                assertThat(stories.workflow(auth).review()).isNotNull();
                assertThat(stories.validate(auth, candidate, review.criticAttemptId())).isEqualTo(review);
                var selected = stories.complete(auth, candidate, review.validationId());
                assertThat(stories.complete(auth, candidate, review.validationId())).isEqualTo(selected);
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(rewritten);
                assertThat(contexts.original(41, auth.resourceId()).text()).isEqualTo(ORIGINAL);
            }

            @Test
            void shouldRejectUnknownAndMismatchedFrozenConstraintVersions() {
                var auth = ready();
                jdbc.update("UPDATE novel_chapter_adaptation SET constraint_version = ? WHERE id = ?", "unknown-v99", auth.resourceId().toString());
                expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> stories.workflow(auth));
                jdbc.update("UPDATE novel_chapter_adaptation SET constraint_version = ? WHERE id = ?", AdaptationStoryEngine.REWRITE_VERSION, auth.resourceId().toString());
                analyze(auth);
                jdbc.update("UPDATE novel_chapter_adaptation_constraint_set SET constraint_version = ? WHERE adaptation_id = ?",
                        AdaptationStoryEngine.VERSION, auth.resourceId().toString());
                expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> stories.workflow(auth));
            }

            @Test
            void shouldCompleteImmutableValidatedVersionAndUseItAsOptimizationBase() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> versions.attempt(41, auth.resourceId(), candidate));
                var review = review(auth, candidate, ADAPTED, rules, null);
                assertThat(review.outcome()).isEqualTo("PASS");
                assertThat(review.progress().status().name()).isEqualTo("PERSISTING");
                assertThat(versions.detail(41, auth.resourceId()).result()).isNull();
                var selected = stories.complete(auth, candidate, review.validationId());
                assertThat(selected.progress().status().name()).isEqualTo("COMPLETED");
                assertThat(stories.complete(auth, candidate, review.validationId())).isEqualTo(selected);
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(ADAPTED);
                assertThat(contexts.original(41, auth.resourceId()).text()).isEqualTo(ORIGINAL);
                var optimized = withCertificate(queued(create(AdaptationRequestKind.OPTIMIZE, auth.resourceId())));
                executionService.claim(optimized);
                var context = executionService.prepare(optimized);
                assertThat(context.fragments().stream().filter(fragment -> fragment.role() == AdaptationContextRole.BASE_INPUT).findFirst().orElseThrow().text()).isEqualTo(ADAPTED);
                assertThat(context.fragments().stream().filter(fragment -> fragment.role() == AdaptationContextRole.TARGET_ORIGINAL).findFirst().orElseThrow().text()).isEqualTo(ORIGINAL);
            }

            @Test
            void shouldRegenerateFromOriginalAfterRealSelectionWithoutOverwritingHistory() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                var review = review(auth, candidate, ADAPTED, rules, null);
                stories.complete(auth, candidate, review.validationId());
                var next = withCertificate(queued(create(AdaptationRequestKind.REGENERATE, auth.resourceId())));
                executionService.claim(next);
                var context = executionService.prepare(next);
                assertThat(context.fragments()).noneMatch(fragment -> fragment.role() == AdaptationContextRole.BASE_INPUT);
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(ADAPTED);
                assertThat(versions.history(41, shelf, chapters.get(1), 20, null).items()).hasSize(2);
            }

            @Test
            void shouldRepairOnlyOnceAndKeepRejectedCandidateReadOnlyWithWarning() {
                var auth = ready();
                var rules = analyze(auth);
                String drifted = ADAPTED.replace("remains unchanged", "is changed");
                var first = generate(auth, drifted, false);
                var rejected = review(auth, first, drifted, rules, null);
                assertThat(rejected.outcome()).isEqualTo("REPAIRABLE");
                assertThat(rejected.progress().status().name()).isEqualTo("REPAIRING");
                assertThat(versions.attempt(41, auth.resourceId(), first).viewStatus()).isEqualTo("REJECTED_BY_CONSTRAINTS");
                var repaired = generate(auth, ADAPTED, true);
                var accepted = review(auth, repaired, ADAPTED, rules, null);
                stories.complete(auth, repaired, accepted.validationId());
                assertThat(versions.detail(41, auth.resourceId()).result().attemptId()).isEqualTo(repaired);
                assertThat(versions.attempt(41, auth.resourceId(), first).content()).isEqualTo(drifted);
                assertThat(executionService.status(auth).callsRemaining()).isZero();
                assertThat(executionService.status(auth).providerMillisRemaining()).isZero();
            }

            @Test
            void shouldTerminateSecondFailureAndNeverOfferFailedVersionAsParent() {
                var auth = ready();
                var rules = analyze(auth);
                String drifted = ADAPTED.replace("remains unchanged", "is changed");
                var first = generate(auth, drifted, false);
                review(auth, first, drifted, rules, null);
                var second = generate(auth, drifted, true);
                var blocked = review(auth, second, drifted, rules, null);
                assertThat(blocked.outcome()).isEqualTo("BLOCKED");
                assertThat(blocked.progress().status().name()).isEqualTo("FAILED");
                assertThat(versions.progress(41, auth.resourceId()).lastErrorCode()).isEqualTo("READER_043");
                assertThat(versions.detail(41, auth.resourceId()).result()).isNull();
                expect(ErrorCode.ADAPTATION_PARENT_INVALID, () -> create(AdaptationRequestKind.OPTIMIZE, auth.resourceId()));
                assertThat(stories.validate(auth, second, blocked.criticAttemptId())).isEqualTo(blocked);
            }

            @Test
            void shouldBlockSafetyRejectedOutputBeforeAndAfterReview() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> versions.attempt(41, auth.resourceId(), candidate));
                var blocked = review(auth, candidate, ADAPTED, rules, "SAFETY");
                assertThat(blocked.contentPolicyOutcome()).isEqualTo("BLOCKED");
                assertThat(versions.progress(41, auth.resourceId()).lastErrorCode()).isEqualTo("READER_053");
                expect(ErrorCode.ADAPTATION_ATTEMPT_NOT_FOUND, () -> versions.attempt(41, auth.resourceId(), candidate));
                assertThat(versions.detail(41, auth.resourceId()).attempts()).isEmpty();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            }

            @Test
            void shouldRejectForgedPlanAndRecordIntentConflictWithoutGenerating() {
                var auth = ready();
                String raw = StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of()).replace("COMPATIBLE", "CONFLICT");
                UUID plan = completeCall(auth, command(Kind.PLAN), success(raw));
                expect(ErrorCode.ADAPTATION_INTENT_CONFLICT, () -> stories.seal(auth, plan));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_constraint_set", Integer.class)).isZero();
                assertThat(stories.fail(auth, "READER_033").status().name()).isEqualTo("FAILED");
                assertThat(executionService.status(auth).callsRemaining()).isEqualTo(4);
                assertThat(stories.fail(auth, "READER_033").status().name()).isEqualTo("FAILED");
            }

            @Test
            void shouldRejectForgedOriginalHashAfterStructuralAcceptanceBeforeConstraintSeal() {
                var auth = ready();
                UUID plan = completeCall(auth, command(Kind.PLAN), success(validPlan().replace(AdaptationText.sha256(ORIGINAL), SHA)));
                expect(ErrorCode.ADAPTATION_PROVIDER_PROTOCOL_INVALID, () -> stories.seal(auth, plan));
                assertThat(executionService.status(auth).status().name()).isEqualTo("ANALYZING");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_constraint_set", Integer.class)).isZero();
            }

            @Test
            void shouldRejectCriticExtrasBeforePersistenceAndStillValidateAnExactLegitimateReport() throws Exception {
                var auth = ready(); var rules = analyze(auth); UUID candidate = generate(auth, ADAPTED, false);
                var command = command(Kind.CRITIC); ledger.reserve(auth, command); var sent = ledger.sendStarted(auth, command.providerAttemptId());
                String valid = StoryFixtures.critic(ADAPTED, rules.mergedSha256(), null, false);
                var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
                ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/checks/0")).put("reasoning", "private-schema-sentinel");
                expect(ErrorCode.ADAPTATION_REQUEST_INVALID, () -> settle(auth, command, sent, auth, success(invalid.toString())));
                assertThat(attemptRow(command)).containsEntry("status", "SEND_STARTED").containsEntry("plan_json", null);
                assertThat(executionService.status(auth).status().name()).isEqualTo("VALIDATING");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_validation", Integer.class)).isZero();
                UUID critic = settle(auth, command, sent, auth, success(valid)).attemptId();
                var validation = stories.validate(auth, candidate, critic); assertThat(validation.outcome()).isEqualTo("PASS");
                assertThat(stories.complete(auth, candidate, validation.validationId()).progress().status().name()).isEqualTo("COMPLETED");
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(ADAPTED);
            }

            @Test
            void shouldRollBackSelectionWhenCompletionUpdateFailsAndOnlyRetryAdoption() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                var review = review(auth, candidate, ADAPTED, rules, null);
                jdbc.execute("CREATE TRIGGER fail_story_complete BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '" + FailExecutionClaimTrigger.class.getName() + "'");
                expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> stories.complete(auth, candidate, review.validationId()));
                jdbc.execute("DROP TRIGGER fail_story_complete");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
                assertThat(executionService.status(auth).status().name()).isEqualTo("PERSISTING");
                stories.complete(auth, candidate, review.validationId());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt", Integer.class)).isEqualTo(3);
            }

            @Test
            void shouldRejectCancellationOldFenceAndTamperedCandidateBeforeCompletion() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                var review = review(auth, candidate, ADAPTED, rules, null);
                var next = withCertificate(authorization(auth, UUID.randomUUID(), 2));
                executionService.claim(next);
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> stories.complete(auth, candidate, review.validationId()));
                jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_text = ? WHERE id = ?", ADAPTED + " Altered.", candidate.toString());
                expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> stories.complete(next, candidate, review.validationId()));
                jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_text = ? WHERE id = ?", ADAPTED, candidate.toString());
                versions.cancel(41, auth.resourceId());
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> stories.complete(next, candidate, review.validationId()));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            }

            @Test
            void shouldReuseCompletedEvidenceAfterTakeoverWithoutAnotherProviderCall() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                var review = review(auth, candidate, ADAPTED, rules, null);
                var next = withCertificate(authorization(auth, UUID.randomUUID(), 2));
                executionService.claim(next);
                stories.complete(next, candidate, review.validationId());
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(ADAPTED);
                assertThat(executionService.status(next).callsRemaining()).isEqualTo(2);
            }

            @Test
            void shouldRejectSkippingStagesAndFailingAnUnsettledCall() {
                var auth = ready();
                ledger.reserve(auth, command(Kind.PLAN));
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> stories.fail(auth, "READER_041"));
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> stories.progress(auth, "ANALYZING", "PLAN", "COMPLETED", "DONE"));
                assertThat(executionService.status(auth).status().name()).isEqualTo("ANALYZING");
            }

            @Test
            void shouldReadFrozenWorkflowWithoutChangingBudgetAndReuseAfterTakeover() {
                var auth = ready();
                assertThat(stories.workflow(auth).attempts()).isEmpty();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                var review = review(auth, candidate, ADAPTED, rules, null);
                var before = stories.workflow(auth);
                assertThat(before.attempts()).hasSize(3);
                assertThat(before.attempts().get(1).outputText()).isEqualTo(ADAPTED);
                assertThat(before.constraints()).isEqualTo(rules);
                assertThat(before.review().validationId()).isEqualTo(review.validationId());
                assertThat(before.review().reportSha256()).isEqualTo(review.reportSha256());
                assertThat(stories.workflow(auth)).isEqualTo(before);
                var next = withCertificate(authorization(auth, UUID.randomUUID(), 2));
                executionService.claim(next);
                var resumed = stories.workflow(next);
                assertThat(resumed.attempts()).isEqualTo(before.attempts());
                assertThat(resumed.review()).isEqualTo(before.review());
                assertThat(resumed.state().callsRemaining()).isEqualTo(before.state().callsRemaining());
                assertThat(resumed.state().providerMillisRemaining()).isEqualTo(before.state().providerMillisRemaining());
                expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> stories.workflow(auth));
                stories.complete(next, resumed.review().candidateAttemptId(), resumed.review().validationId());
                var stopped = stories.workflow(next);
                assertThat(stopped.state().status().name()).isEqualTo("COMPLETED");
                assertThat(stopped.attempts()).isEmpty();
                assertThat(stopped.constraints()).isNull();
                assertThat(stopped.review()).isNull();
            }

            @Test
            void shouldReturnBoundRepairFeedbackButNoContentAfterCancelOrFailure() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                review(auth, candidate, ADAPTED, rules, "INTENT");
                var workflow = stories.workflow(auth);
                assertThat(workflow.state().status().name()).isEqualTo("REPAIRING");
                assertThat(workflow.review().round()).isEqualTo(1);
                assertThat(workflow.review().outcome()).isEqualTo("REPAIRABLE");
                assertThat(workflow.review().contentPolicyOutcome()).isEqualTo("PASS");
                assertThat(workflow.review().criticJson()).contains("INTENT");
                assertThat(workflow.review().reportSha256()).isEqualTo(AdaptationText.sha256(workflow.review().reportJson()));
                assertThat(workflow.toString()).doesNotContain(ADAPTED, ORIGINAL);
                versions.cancel(41, auth.resourceId());
                var stopped = stories.workflow(auth);
                assertThat(stopped.state().stopRequested()).isTrue();
                assertThat(stopped.attempts()).isEmpty();
                assertThat(stopped.review()).isNull();
            }

            @Test
            void shouldExposeOnlyMetadataForPendingAndArchivedCalls() {
                var auth = ready();
                var command = command(Kind.PLAN);
                ledger.reserve(auth, command);
                var pending = stories.workflow(auth).attempts().getFirst();
                assertThat(pending.status()).isEqualTo("REGISTERED");
                assertThat(pending.structuredJson()).isNull();
                var permit = ledger.sendStarted(auth, command.providerAttemptId());
                settle(auth, command, permit, null, success(StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of())));
                var archived = stories.workflow(auth).attempts().getFirst();
                assertThat(archived.status()).isEqualTo("SUCCEEDED");
                assertThat(archived.archivedOnly()).isTrue();
                assertThat(archived.structuredJson()).isNull();
                assertThat(archived.outputText()).isNull();
            }

            @Test
            void shouldRejectTamperedWorkflowEvidenceInsteadOfReturningIt() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                review(auth, candidate, ADAPTED, rules, null);
                jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_text = ? WHERE id = ?", "Tampered output", candidate.toString());
                expect(ErrorCode.ADAPTATION_CONTEXT_INCOMPLETE, () -> stories.workflow(auth));
                jdbc.update("UPDATE novel_chapter_adaptation_attempt SET output_text = ? WHERE id = ?", ADAPTED, candidate.toString());
                jdbc.update("UPDATE novel_chapter_adaptation_validation SET report_sha256 = ? WHERE adaptation_id = ?", SHA, auth.resourceId().toString());
                expect(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT, () -> stories.workflow(auth));
            }

            @Test
            void shouldNotReturnSafetyBlockedWorkflowContent() {
                var auth = ready();
                var rules = analyze(auth);
                var candidate = generate(auth, ADAPTED, false);
                review(auth, candidate, ADAPTED, rules, "SAFETY");
                var workflow = stories.workflow(auth);
                assertThat(workflow.state().status().name()).isEqualTo("FAILED");
                assertThat(workflow.errorCode()).isEqualTo("READER_053");
                assertThat(workflow.attempts()).isEmpty();
                assertThat(workflow.constraints()).isNull();
                assertThat(workflow.review()).isNull();
            }

            @Test
            void shouldRequireOnlineAuthorizationAndEmptyRequestForWorkflowHttp() throws Exception {
                var http = http(stories);
                analyze(http.authorization());
                String path = http.path() + "/workflow";
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store, private"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.attempts[0].callKind").value("PLAN"));
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).content("{}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path + "?ownerId=41")))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
                http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).secure(true)
                                .requestAttr("jakarta.servlet.request.X509Certificate", http.certificates()).header("X-Reader-Attempt-Settlement", "settle-v1.fixture.token"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
                org.mockito.Mockito.when(http.authority().activeUntil(org.mockito.ArgumentMatchers.any()))
                        .thenThrow(new ChapterAdaptationException(ErrorCode.ADAPTATION_AUTHORIZATION_UNAVAILABLE));
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
            }

            private com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels.Constraints analyze(WorkloadAuthorization auth) {
                String plan = StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of());
                UUID planId = completeCall(auth, command(Kind.PLAN), success(plan));
                var rules = stories.seal(auth, planId);
                assertThat(stories.seal(auth, planId)).isEqualTo(rules);
                return rules;
            }

            @Test
            void shouldRollBackConstraintAndValidationInsertTogetherWithTheirStateTransitions() {
                var auth = ready();
                UUID plan = completeCall(auth, command(Kind.PLAN), success(StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of())));
                jdbc.execute("CREATE TRIGGER fail_story_stage BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '" + FailExecutionClaimTrigger.class.getName() + "'");
                expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> stories.seal(auth, plan));
                jdbc.execute("DROP TRIGGER fail_story_stage");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_constraint_set", Integer.class)).isZero();
                assertThat(executionService.status(auth).status().name()).isEqualTo("ANALYZING");
                var rules = stories.seal(auth, plan);
                UUID candidate = generate(auth, ADAPTED, false);
                UUID critic = completeCall(auth, command(Kind.CRITIC), success(StoryFixtures.critic(ADAPTED, rules.mergedSha256(), null, false)));
                jdbc.execute("CREATE TRIGGER fail_story_stage BEFORE UPDATE ON novel_chapter_adaptation FOR EACH ROW CALL '" + FailExecutionClaimTrigger.class.getName() + "'");
                expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> stories.validate(auth, candidate, critic));
                jdbc.execute("DROP TRIGGER fail_story_stage");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_validation", Integer.class)).isZero();
                assertThat(executionService.status(auth).status().name()).isEqualTo("VALIDATING");
                assertThat(stories.validate(auth, candidate, critic).outcome()).isEqualTo("PASS");
            }

            @Test
            void shouldDriveStoryHttpFromStoredPlanThroughValidationAndSelection() throws Exception {
                var http = http(stories);
                var auth = http.authorization();
                UUID plan = completeCall(auth, command(Kind.PLAN), success(StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of())));
                var sealedResponse = post(http, "/constraints", Map.of("planAttemptId", plan.toString()))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store, private"))
                        .andReturn().getResponse().getContentAsString();
                String rulesSha = mapper.readTree(sealedResponse).path("mergedSha256").textValue();
                UUID candidate = completeCall(auth, command(Kind.GENERATE), new AdaptationAttemptModels.Terminal("SUCCEEDED", ADAPTED, null, "stop", null, null, null, 200, null, null));
                post(http, "/progress", Map.of("expectedStatus", "GENERATING", "expectedStage", "GENERATE", "nextStatus", "VALIDATING", "nextStage", "CRITIC_PENDING"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
                UUID critic = completeCall(auth, command(Kind.CRITIC), success(StoryFixtures.critic(ADAPTED, rulesSha, null, false)));
                var validationResponse = post(http, "/validations", Map.of("candidateAttemptId", candidate.toString(), "criticAttemptId", critic.toString()))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.outcome").value("PASS"))
                        .andReturn().getResponse().getContentAsString();
                String validation = mapper.readTree(validationResponse).path("validationId").textValue();
                post(http, "/complete", Map.of("candidateAttemptId", candidate.toString(), "validationId", validation, "outputText", "Injected replacement"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
                post(http, "/complete", Map.of("candidateAttemptId", candidate.toString(), "validationId", validation))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.progress.status").value("COMPLETED"));
                assertThat(versions.detail(41, auth.resourceId()).result().content()).isEqualTo(ADAPTED);
                org.mockito.Mockito.when(http.authority().activeUntil(org.mockito.ArgumentMatchers.any())).thenReturn(null);
                post(http, "/complete", Map.of("candidateAttemptId", candidate.toString(), "validationId", validation))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            }

            @Test
            void shouldRejectUnknownFieldsAndSettlementCapabilityOnEveryStoryRoute() throws Exception {
                var http = http(stories);
                var command = command(Kind.PLAN);
                ledger.reserve(http.authorization(), command);
                var permit = ledger.sendStarted(http.authorization(), command.providerAttemptId());
                for (String path : List.of("/constraints", "/progress", "/validations", "/complete", "/fail")) {
                    post(http, path, Map.of("ownerId", "41", "outputText", "not-accepted"))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
                    http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + path).secure(true)
                                    .requestAttr("jakarta.servlet.request.X509Certificate", http.certificates()).header("Authorization", "Bearer " + permit.attemptSettlementToken())
                                    .contentType("application/json").content("{}"))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
                }
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_selection", Integer.class)).isZero();
            }

            private org.springframework.test.web.servlet.ResultActions post(HttpFixture fixture, String path, Map<String, String> body) throws Exception {
                return fixture.mvc().perform(fixture.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fixture.path() + path))
                        .contentType("application/json").content(mapper.writeValueAsString(body)));
            }
            private UUID generate(WorkloadAuthorization auth, String output, boolean repair) {
                var payload = new AdaptationAttemptModels.Terminal("SUCCEEDED", output, null, "stop", null, null, null, 200, null, null);
                UUID candidate = completeCall(auth, command(repair ? Kind.REPAIR : Kind.GENERATE), payload);
                var progress = stories.progress(auth, repair ? "REPAIRING" : "GENERATING", repair ? "REPAIR" : "GENERATE", "VALIDATING", "CRITIC_PENDING");
                assertThat(stories.progress(auth, repair ? "REPAIRING" : "GENERATING", repair ? "REPAIR" : "GENERATE", "VALIDATING", "CRITIC_PENDING")).isEqualTo(progress);
                return candidate;
            }
            private com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels.Validation review(WorkloadAuthorization auth, UUID candidate, String output,
                    com.yuyutian.mytools.reader.model.adaptation.AdaptationStoryModels.Constraints rules, String failedCategory) {
                String raw = StoryFixtures.critic(output, rules.mergedSha256(), failedCategory, false);
                UUID critic = completeCall(auth, command(Kind.CRITIC), success(raw));
                var review = stories.validate(auth, candidate, critic);
                assertThat(stories.validate(auth, candidate, critic)).isEqualTo(review);
                return review;
            }
        }

        @Test
        void shouldEnforceFiveStageCeilingAndTheShared660SecondBudget() {
            var auth = ready();
            var plan = command(Kind.PLAN);
            UUID planId = completeCall(auth, plan, success(validPlan()));
            // 约束及阶段转换仍使用 SQL 夹具；此测试只证明真实账本的五阶段预算，不证明模型流水线。
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_constraint_set (adaptation_id, owner_id, plan_attempt_id,
                        deterministic_json, supplement_json, merged_json, deterministic_sha256, supplement_sha256, merged_sha256,
                        constraint_version, created_by_execution_id, created_at)
                    VALUES (?, 41, ?, '{}', '{}', '{}', ?, ?, ?, 'rules-v1', ?, ?)
                    """, auth.resourceId().toString(), planId.toString(), SHA, SHA, SHA, auth.executionId().toString(), TIME);
            stageFixture(auth, "GENERATING");
            UUID candidateId = completeCall(auth, command(Kind.GENERATE), new AdaptationAttemptModels.Terminal("SUCCEEDED", OUTPUT, null, "stop", null, null, null, 200, null, null));
            stageFixture(auth, "VALIDATING");
            UUID criticId = completeCall(auth, command(Kind.CRITIC), success(StoryFixtures.critic(OUTPUT, SHA, null, false)));
            jdbc.update("""
                    INSERT INTO novel_chapter_adaptation_validation (id, adaptation_id, owner_id, candidate_attempt_id, critic_attempt_id,
                        validation_round, deterministic_version, deterministic_json, critic_version, critic_json, outcome, report_json, report_sha256, created_at)
                    VALUES (?, ?, 41, ?, ?, 1, 'fixture', '{}', 'fixture', '{}', 'REPAIRABLE', '{}', ?, ?)
                    """, UUID.randomUUID().toString(), auth.resourceId().toString(), candidateId.toString(), criticId.toString(), SHA, TIME);
            stageFixture(auth, "REPAIRING");
            completeCall(auth, command(Kind.REPAIR), new AdaptationAttemptModels.Terminal("SUCCEEDED", OUTPUT, null, "stop", null, null, null, 200, null, null));
            stageFixture(auth, "VALIDATING");
            completeCall(auth, command(Kind.CRITIC), success(StoryFixtures.critic(OUTPUT, SHA, null, false)));
            assertThat(executionService.status(auth).callsRemaining()).isZero();
            assertThat(executionService.status(auth).providerMillisRemaining()).isZero();
            assertThat(jdbc.queryForObject("SELECT SUM(reserved_millis) FROM novel_chapter_adaptation_attempt_reservation", Long.class)).isEqualTo(660000);
            assertThat(jdbc.queryForObject("SELECT repair_count FROM novel_chapter_adaptation WHERE id = ?", Integer.class, auth.resourceId().toString())).isEqualTo(1);
            expect(ErrorCode.ADAPTATION_EXECUTION_FENCED, () -> ledger.reserve(auth, command(Kind.CRITIC)));
        }

        @Test
        void shouldRetryOnlyTheSameTerminalWriteAfterDatabaseFailure() {
            var auth = ready();
            var command = command(Kind.PLAN);
            ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            jdbc.execute("CREATE TRIGGER fail_attempt_terminal BEFORE UPDATE ON novel_chapter_adaptation_attempt FOR EACH ROW CALL '"
                    + FailExecutionClaimTrigger.class.getName() + "'");
            expect(ErrorCode.ADAPTATION_PERSISTENCE_UNAVAILABLE, () -> settle(auth, command, sent, auth, success(validPlan())));
            jdbc.execute("DROP TRIGGER fail_attempt_terminal");
            assertThat(attemptRow(command).get("status")).isEqualTo("SEND_STARTED");
            assertThat(settle(auth, command, sent, auth, success(validPlan()))).extracting(AdaptationAttemptModels.Settlement::status).isEqualTo("SUCCEEDED");
            assertThat(attemptRow(command).get("transmission_count")).isEqualTo(1);
            assertThat(executionService.status(auth).callsRemaining()).isEqualTo(4);
        }

        private UUID completeCall(WorkloadAuthorization auth, AdaptationAttemptModels.Reserve command, AdaptationAttemptModels.Terminal payload) {
            var reserved = ledger.reserve(auth, command);
            var sent = ledger.sendStarted(auth, command.providerAttemptId());
            settle(auth, command, sent, auth, payload);
            return reserved.attemptId();
        }
        private void stageFixture(WorkloadAuthorization auth, String stage) {
            jdbc.update("UPDATE novel_chapter_adaptation SET status = ? WHERE id = ?", stage, auth.resourceId().toString());
        }

        @Test
        void shouldDriveAuthorizedAttemptHttpThroughReserveSendAndSettlement() throws Exception {
            var http = http();
            var command = command(Kind.PLAN);
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts"))
                            .contentType("application/json").content(mapper.writeValueAsString(command)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("REGISTERED"));
            var sent = http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts/" + command.providerAttemptId() + "/send-started")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.maySend").value(true))
                    .andReturn().getResponse().getContentAsString();
            String receipt = mapper.readTree(sent).path("attemptSettlementToken").textValue();
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(http.path() + "/attempts/" + command.providerAttemptId()))
                            .header("X-Reader-Attempt-Settlement", receipt).contentType("application/json").content(mapper.writeValueAsString(success(validPlan()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.archivedOnly").value(false))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store, private"));
            assertThat(attemptRow(command).get("status")).isEqualTo("SUCCEEDED");
        }

        @Test
        void shouldRejectArbitraryInputAndMalformedBodiesBeforeAnyReservation() throws Exception {
            var http = http();
            String valid = mapper.writeValueAsString(command(Kind.PLAN));
            for (String invalid : List.of(valid.substring(0, valid.length() - 1) + ",\"ownerId\":42}", valid + " {}",
                    valid.replace("\"callKind\":\"PLAN\"", "\"callKind\":\"PLAN\",\"callKind\":\"PLAN\""),
                    valid.replace("\"callKind\":\"PLAN\"", "\"callKind\":1"), "x".repeat(1025))) {
                http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts"))
                                .contentType("application/json").content(invalid))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            }
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts?ownerId=42"))
                            .contentType("application/json").content(valid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts"))
                            .contentType("application/json").header("Content-Encoding", "gzip").content(valid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts")
                            .header("Authorization", "Bearer " + http.token()).contentType("application/json").content(valid))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM novel_chapter_adaptation_attempt", Integer.class)).isZero();
        }

        @Test
        void shouldAllowRevokedAssertionOnlyToArchiveUsingIndependentTlsBoundCapability() throws Exception {
            var http = http();
            var command = command(Kind.PLAN);
            ledger.reserve(http.authorization(), command);
            var sent = ledger.sendStarted(http.authorization(), command.providerAttemptId());
            org.mockito.Mockito.when(http.authority().activeUntil(org.mockito.ArgumentMatchers.any())).thenReturn(null);
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(http.path() + "/attempts/" + command.providerAttemptId()))
                            .header("X-Reader-Attempt-Settlement", sent.attemptSettlementToken()).contentType("application/json").content(mapper.writeValueAsString(success(validPlan()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.archivedOnly").value(true));
            http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/attempts").secure(true)
                            .requestAttr("jakarta.servlet.request.X509Certificate", http.certificates()).header("Authorization", "Bearer " + sent.attemptSettlementToken())
                            .contentType("application/json").content(mapper.writeValueAsString(command(Kind.PLAN))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(http.path() + "/prepare-context").secure(true)
                            .requestAttr("jakarta.servlet.request.X509Certificate", http.certificates()).header("Authorization", "Bearer " + sent.attemptSettlementToken()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertThat(executionService.status(http.authorization()).status().name()).isEqualTo("ANALYZING");
        }

        @Test
        void shouldRequireReceiptBeforeReadingSettlementBodyAndSupportCertificateOnlyArchival() throws Exception {
            var http = http();
            var command = command(Kind.PLAN);
            ledger.reserve(http.authorization(), command);
            var sent = ledger.sendStarted(http.authorization(), command.providerAttemptId());
            http.mvc().perform(http.request(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(http.path() + "/attempts/" + command.providerAttemptId()))
                            .contentType("application/json").content("invalid-body-sentinel"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-body-sentinel"))));
            http.mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(http.path() + "/attempts/" + command.providerAttemptId()).secure(true)
                            .requestAttr("jakarta.servlet.request.X509Certificate", http.certificates()).header("X-Reader-Attempt-Settlement", sent.attemptSettlementToken())
                            .contentType("application/json").content(mapper.writeValueAsString(success(validPlan()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.archivedOnly").value(true));
        }

        private HttpFixture http() throws Exception {
            return http(null);
        }
        private HttpFixture http(AdaptationStoryRepository storyRepository) throws Exception {
            var auth = ready();
            var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var authority = org.mockito.Mockito.mock(ReaderWorkloadAuthority.class);
            org.mockito.Mockito.when(authority.publicKey("fixture")).thenReturn(key.getPublic());
            org.mockito.Mockito.when(authority.activeUntil(org.mockito.ArgumentMatchers.any())).thenReturn(NOW.plusSeconds(60));
            var authorizer = new ReaderWorkloadAuthorizer(WorkloadAuthorizationFixtures.properties(), authority, mapper, clock);
            var controllers = new ArrayList<Object>(List.of(new AdaptationAttemptController(authorizer, new AdaptationAttemptService(ledger), mapper),
                    new AdaptationExecutionController(authorizer, executionService)));
            if (storyRepository != null) controllers.add(new AdaptationStoryController(authorizer, new AdaptationStoryService(storyRepository), mapper));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controllers.toArray())
                    .setControllerAdvice(new ReaderExceptionHandler()).addFilters(new ReaderAdaptationResponseFilter()).build();
            String token = WorkloadAuthorizationFixtures.token(key, WorkloadAuthorizationFixtures.claims(WorkloadResource.CHAPTER_ADAPTATION,
                    auth.resourceId(), auth.taskInstanceId(), auth.executionId(), 1, NOW));
            return new HttpFixture(mvc, "/api/internal/v1/chapter-adaptations/" + auth.resourceId() + "/executions/" + auth.executionId(), token,
                    new java.security.cert.X509Certificate[]{WorkloadAuthorizationFixtures.certificate()}, authority, auth);
        }
        private record HttpFixture(org.springframework.test.web.servlet.MockMvc mvc, String path, String token,
                                   java.security.cert.X509Certificate[] certificates, ReaderWorkloadAuthority authority, WorkloadAuthorization authorization) {
            private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
                return request.secure(true).requestAttr("jakarta.servlet.request.X509Certificate", certificates).header("Authorization", "Bearer " + token);
            }
            /** 即使测试失败打印夹具，也不输出工作负载 token。 */
            @Override
            public String toString() { return "HttpFixture[token=REDACTED]"; }
        }
        private WorkloadAuthorization withCertificate(WorkloadAuthorization auth) {
            return new WorkloadAuthorization(auth.resource(), auth.resourceId(), auth.taskInstanceId(), auth.executionId(), auth.fencingToken(), auth.authorizedUntil(), certificate);
        }
        private AdaptationAttemptModels.Reserve command(Kind kind) { return new AdaptationAttemptModels.Reserve(UUID.randomUUID(), kind, SHA); }
        private String validPlan() { return StoryFixtures.plan(ORIGINAL, List.of("leaves at dawn"), "destination remains unchanged", List.of()); }
        private AdaptationAttemptModels.Terminal success(String json) {
            return new AdaptationAttemptModels.Terminal("SUCCEEDED", null, json, "stop", "fixture-request", 20L, 10L, 200, null, SHA);
        }
        private Map<String, Object> attemptRow(AdaptationAttemptModels.Reserve command) {
            return jdbc.queryForMap("SELECT * FROM novel_chapter_adaptation_attempt WHERE provider_attempt_id = ?", command.providerAttemptId().toString());
        }
        private AdaptationAttemptModels.Settlement settle(WorkloadAuthorization original, AdaptationAttemptModels.Reserve command,
                                                         AdaptationAttemptModels.SendPermit sent, WorkloadAuthorization current, AdaptationAttemptModels.Terminal payload) {
            return ledger.settle(original.resourceId(), original.executionId(), command.providerAttemptId(), certificate, sent.attemptSettlementToken(), current, payload);
        }
        private void insertBucket(String kind, String scope) {
            jdbc.update("INSERT INTO novel_adaptation_provider_circuit (bucket_id, bucket_kind, provider_deployment_id, scope_key, state) VALUES (?, ?, ?, ?, 'CLOSED')",
                    AdaptationText.fingerprint("fixture-circuit-v1", List.of(kind, scope)), kind, binary("fixture"), binary(scope));
        }
    }

    private UUID completeFixture(UUID adaptationId) {
        // 上下文来自真实封存，只有生成和校验结果由固定夹具建立，不将其算作真实 Provider 证据。
        UUID candidate = attempt(adaptationId, 1, "GENERATE", OUTPUT);
        UUID critic = attempt(adaptationId, 2, "CRITIC", null);
        UUID validation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_validation (id, adaptation_id, owner_id, candidate_attempt_id, critic_attempt_id,
                    validation_round, deterministic_version, deterministic_json, critic_version, critic_json, outcome, report_json, report_sha256, created_at, content_policy_outcome)
                VALUES (?, ?, 41, ?, ?, 1, 'fixture', '{}', 'fixture', '{}', 'PASS', '{}', ?, ?, 'PASS')
                """, validation.toString(), adaptationId.toString(), candidate.toString(), critic.toString(), SHA, TIME);
        jdbc.update("INSERT INTO novel_chapter_adaptation_selection VALUES (?, 41, ?, ?, ?)",
                adaptationId.toString(), candidate.toString(), validation.toString(), TIME);
        jdbc.update("UPDATE novel_chapter_adaptation SET status = 'COMPLETED', current_stage = 'DONE', finished_at = ?, attempt_count = 2 WHERE id = ?",
                TIME, adaptationId.toString());
        return candidate;
    }

    private UUID attempt(UUID adaptationId, int number, String kind, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO novel_chapter_adaptation_attempt (id, adaptation_id, owner_id, attempt_no, call_kind, task_instance_id,
                    execution_id, fencing_token, scheduler_attempt_no, provider_deployment_id, model_id, credential_generation,
                    provider_attempt_id, request_sha256, output_text, output_sha256, output_codepoint_count, status,
                    viewable_candidate, settlement_token_sha256, settlement_expires_at, chapter_delete_epoch, terminal_payload_sha256, created_at, completed_at)
                VALUES (?, ?, 41, ?, ?, ?, ?, 1, 1, ?, ?, 1, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, 0, ?, ?, ?)
                """, id.toString(), adaptationId.toString(), number, kind, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                binary("fixture"), binary("Fixture-Model"), UUID.randomUUID().toString(), SHA, text,
                text == null ? null : AdaptationText.sha256(text), text == null ? null : text.codePointCount(0, text.length()),
                text != null, SHA, Timestamp.from(NOW.plusSeconds(840)), SHA, TIME, TIME);
        return id;
    }

    private static AdaptationContextFragment fragment(AdaptationContextSnapshot snapshot, AdaptationContextRole role) {
        return snapshot.fragments().stream().filter(item -> item.role() == role).findFirst().orElseThrow();
    }

    private static byte[] binary(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void expect(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ChapterAdaptationException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        /** 返回固定 UTC 时区。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 测试不允许改变时区。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回受测试控制的时刻。 */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
