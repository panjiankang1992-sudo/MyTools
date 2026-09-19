package com.yuyutian.mytools.reader.service.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderShelfChapterProperties;
import com.yuyutian.mytools.reader.config.ReaderAdaptationProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeModels;
import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.model.adaptation.ReaderRuntimeInvocation;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationCommand;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationExecutionFence;
import com.yuyutian.mytools.reader.model.adaptation.AdaptationRequestKind;
import com.yuyutian.mytools.reader.model.adaptation.ShelfChapterModels;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository.SourceExecutionSnapshot;
import com.yuyutian.mytools.reader.repository.adaptation.ShelfChapterRepository;
import com.yuyutian.mytools.reader.repository.adaptation.AdaptationContextRepository;
import com.yuyutian.mytools.reader.repository.adaptation.ChapterAdaptationRepository;
import com.yuyutian.mytools.reader.service.ReaderRuntimeClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用真实迁移、事务和加密，只有运行时网络由固定夹具替换。 */
class SourceShelfChapterServiceTest {
    private static final String SOURCE = "https://source.example.invalid";
    private static final String BOOK = "https://book.example.invalid/book";
    private static final String CHAPTER = "https://book.example.invalid/chapter/";
    private final UUID shelfId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock();
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ShelfChapterRepository repository;
    private SourceShelfChapterService service;
    private ReaderRuntimeClient runtime;
    private SourceLocatorCipher cipher;
    private SourceLocatorPolicy policy;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:source_chapters_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var properties = new ReaderShelfChapterProperties(true, true, "", 180, 3, 2);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        cipher = new SourceLocatorCipher("fixture", Map.of("fixture", key));
        policy = new SourceLocatorPolicy() {
            /** 固定公开地址夹具，不进行外部 DNS 查询。 */
            @Override
            protected InetAddress[] resolve(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})};
            }
        };
        repository = new ShelfChapterRepository(jdbc, mapper, new DataSourceTransactionManager(dataSource), properties, cipher, clock);
        runtime = mock(ReaderRuntimeClient.class);
        service = new SourceShelfChapterService(repository, new DiscoveryRepository(jdbc, mapper), runtime, policy, cipher, properties);
        String metadata = mapper.writeValueAsString(Map.of("sourceId", SOURCE, "resourceUri", BOOK, "origin", "source", "format", "txt"));
        jdbc.update("INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at) VALUES (?, 41, ?, ?, ?, ?)",
                shelfId.toString(), shelfId.toString(), metadata, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 41, ?, 'Source', ?, TRUE, 1, ?, ?)
                """, sourceId.toString(), sourceId.toString(), SOURCE, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        insertSourceVersion(1);
        when(runtime.catalog(any(ReaderRuntimeInvocation.class), any(SourceExecutionSnapshot.class), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return catalog("First", "Second");
                });
        when(runtime.content(any(ReaderRuntimeInvocation.class), any(SourceExecutionSnapshot.class), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new BookSourceRuntimeModels.Content("text", "A complete original chapter.\n", List.of());
                });
    }

    @AfterEach
    void shutdown() {
        if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void shouldConnectCanonicalSourceReadingToAtomicAdaptationSnapshot() {
        service.ensure(41, shelfId, "prepare-context-source");
        assertThat(service.prepareOne("source-context-worker")).isTrue();
        var catalog = service.catalog(41, shelfId, 20, null);
        UUID target = catalog.items().getFirst().chapterId();
        var original = service.content(41, shelfId, target);
        byte[] deployment = "context-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] model = "Context-Fixture-Model".getBytes(StandardCharsets.UTF_8);
        String hash = AdaptationText.sha256("fixture-contract");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO novel_adaptation_provider_deployment VALUES (?, 'fixture', ?, 1, ?, TRUE, ?)", deployment, model, hash, now);
        AdaptationConsentFixtures.installAndAccept(jdbc, "context-fixture", "context-fixture", "context-fixture", hash, clock);
        var manager = new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var versions = new ChapterAdaptationRepository(jdbc, manager, new ObjectMapper().findAndRegisterModules(),
                new ReaderAdaptationProperties(true, true, "context-fixture", "context-fixture", "fixture", "fixture", 2), clock);
        UUID id = versions.create(new AdaptationCommand(41, shelfId, target, AdaptationRequestKind.INITIAL, null,
                "create-from-source", "Keep the original narrative outcome.", catalog.bindingRevision(), catalog.catalogRevision(), original.sha256())).adaptationId();
        var execution = new AdaptationExecutionFence(id, UUID.randomUUID(), UUID.randomUUID(), 1, 0, clock.instant().plusSeconds(300));
        // 仅调度授权前置状态由夹具建立，目录准备、取文、版本创建和封存均执行实际实现。
        jdbc.update("""
                UPDATE novel_chapter_adaptation SET task_instance_id = ?, current_execution_id = ?, fencing_token = 1,
                    status = 'CONTEXT_FREEZING', current_stage = 'FETCH_TARGET', dispatch_attempt_count = 1 WHERE id = ?
                """, execution.taskInstanceId().toString(), execution.executionId().toString(), id.toString());
        var contexts = new AdaptationContextRepository(jdbc, mapper, manager, clock);
        var snapshot = new AdaptationContextService(contexts, service).prepare(execution);
        assertThat(snapshot.identity().targetChapterId()).isEqualTo(target);
        assertThat(snapshot.identity().catalogChapterCount()).isEqualTo(2);
        assertThat(snapshot.originalContentSha256()).isEqualTo(original.sha256());
        assertThat(snapshot.fragments()).hasSize(4);
        assertThat(contexts.original(41, id).text()).isEqualTo(original.text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"txt", "epub", "mobi", "azw3", "unknown"})
    void shouldPrepareSupportedSourceFormatsWithoutChangingShelfMetadata(String format) throws Exception {
        String metadata = mapper.writeValueAsString(Map.of("sourceId", SOURCE, "resourceUri", BOOK,
                "origin", "source", "format", format));
        jdbc.update("UPDATE shelf_book SET metadata_json = ? WHERE id = ?", metadata, shelfId.toString());
        service.ensure(41, shelfId, "prepare-source-format");
        assertThat(service.prepareOne("source-format-worker")).isTrue();
        var catalog = service.catalog(41, shelfId, 20, null);
        assertThat(service.content(41, shelfId, catalog.items().getFirst().chapterId()).text())
                .isEqualTo("A complete original chapter.\n");
        assertThat(repository.shelf(41, shelfId).format()).isEqualTo(format);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "jpg", "mp3", "", "invalid"})
    void shouldRejectNonTextOrMissingSourceFormatsBeforeRuntime(String format) throws Exception {
        String metadata = mapper.writeValueAsString(Map.of("sourceId", SOURCE, "resourceUri", BOOK,
                "origin", "source", "format", format));
        jdbc.update("UPDATE shelf_book SET metadata_json = ? WHERE id = ?", metadata, shelfId.toString());
        assertThatThrownBy(() -> service.ensure(41, shelfId, "reject-source-format"))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE));
        verifyNoInteractions(runtime);
    }

    @Test
    void shouldStillRejectNonTextContentForUnknownSourceFormat() throws Exception {
        String metadata = mapper.writeValueAsString(Map.of("sourceId", SOURCE, "resourceUri", BOOK,
                "origin", "source", "format", "unknown"));
        jdbc.update("UPDATE shelf_book SET metadata_json = ? WHERE id = ?", metadata, shelfId.toString());
        service.ensure(41, shelfId, "unknown-source-non-text");
        service.prepareOne("unknown-source-worker");
        var chapter = service.catalog(41, shelfId, 20, null).items().getFirst().chapterId();
        when(runtime.content(any(ReaderRuntimeInvocation.class), any(SourceExecutionSnapshot.class), anyString()))
                .thenReturn(new BookSourceRuntimeModels.Content("image", "", List.of()));
        assertThatThrownBy(() -> service.content(41, shelfId, chapter))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAPTER_ADAPTATION_INELIGIBLE));
    }

    @Test
    void shouldPrepareReadAndPageOnlyOwnedShelfChaptersWithoutOverwritingBookState() {
        var preparing = service.ensure(41, shelfId, "request-1");
        assertThat(preparing.status()).isEqualTo("PREPARING");
        assertThat(service.ensure(41, shelfId, "request-2")).isEqualTo(preparing);
        assertThat(count("shelf_book_preparation_dispatch")).isEqualTo(1);
        verifyNoInteractions(runtime);
        assertThat(service.prepareOne("worker-1")).isTrue();
        assertThat(service.capability(41, shelfId).status()).isEqualTo("READY");
        assertThat(count("shelf_book_catalog_projection_staging")).isZero();
        var first = service.catalog(41, shelfId, 1, null);
        var second = service.catalog(41, shelfId, 1, first.nextCursor());
        assertThat(first.items()).hasSize(1);
        assertThat(second.items()).extracting(ShelfChapterModels.Chapter::index).containsExactly(1);
        assertThat(second.nextCursor()).isNull();
        var content = service.content(41, shelfId, first.items().getFirst().chapterId());
        assertThat(content.text()).isEqualTo("A complete original chapter.\n");
        assertThat(content.sha256()).isEqualTo(AdaptationText.sha256(content.text()));
        assertThat(service.catalog(41, shelfId, 10, null).items().getFirst().sourceSha256()).isEqualTo(content.sha256());
        assertThat(jdbc.queryForObject("SELECT version FROM shelf_book WHERE id = ?", Long.class, shelfId.toString())).isEqualTo(1);
        assertThat(count("reading_progress")).isZero();
        assertThat(jdbc.queryForList("SELECT locator_ciphertext FROM shelf_book_source_locator").toString())
                .doesNotContain(SOURCE, BOOK, CHAPTER);
    }

    @Test
    void shouldRejectCrossOwnerMissingChapterAndTamperedCursor() {
        service.ensure(41, shelfId, "request");
        service.prepareOne("worker");
        var page = service.catalog(41, shelfId, 1, null);
        assertThatThrownBy(() -> service.ensure(42, shelfId, "request")).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> service.catalog(42, shelfId, 1, page.nextCursor())).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> service.content(42, shelfId, page.items().getFirst().chapterId()))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.READER_STATE_NOT_FOUND));
        assertThatThrownBy(() -> service.content(41, shelfId, UUID.randomUUID())).isInstanceOf(ChapterAdaptationException.class);
        String changed = page.nextCursor().substring(0, page.nextCursor().length() - 8) + "modified";
        assertThatThrownBy(() -> service.catalog(41, shelfId, 1, changed)).isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRejectIncompleteManifestAndNeverPublishPartialCatalog() {
        var claim = initialClaim();
        var first = staged(claim, 0, "First");
        var second = staged(claim, 1, "Second");
        String manifest = ShelfChapterRepository.manifest(List.of(first.sha256(), second.sha256()));
        repository.declareManifest(claim, 2, manifest);
        repository.stage(claim, List.of(first));
        assertThatThrownBy(() -> repository.seal(claim, 2, manifest)).isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> repository.seal(claim, 1, ShelfChapterRepository.manifest(List.of(first.sha256()))))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThat(count("shelf_book_chapter")).isZero();
        repository.stage(claim, List.of(second));
        repository.seal(claim, 2, manifest);
        assertThat(count("shelf_book_chapter")).isEqualTo(2);
    }

    @Test
    void shouldReclaimExpiredLeaseAndRejectOldWritesWithoutResettingInvocation() {
        var old = initialClaim();
        var item = staged(old, 0, "First");
        repository.declareManifest(old, 1, ShelfChapterRepository.manifest(List.of(item.sha256())));
        assertThat(repository.claim("other-worker")).isEmpty();
        clock.advance(181);
        var current = repository.claim("new-worker").orElseThrow();
        assertThat(current.invocationId()).isEqualTo(old.invocationId());
        assertThat(current.epoch()).isGreaterThan(old.epoch());
        assertThat(current.attempt()).isEqualTo(2);
        assertThatThrownBy(() -> repository.stage(old, List.of(item))).isInstanceOf(ChapterAdaptationException.class);
        repository.fail(old, ErrorCode.CHAPTER_SOURCE_CHANGED);
        repository.stage(current, List.of(item));
        repository.seal(current, 1, ShelfChapterRepository.manifest(List.of(item.sha256())));
        assertThat(service.capability(41, shelfId).status()).isEqualTo("READY");
    }

    @Test
    void shouldAcceptIdenticalStagingReplayButRejectDifferentContentOrManifest() {
        var claim = initialClaim();
        var item = staged(claim, 0, "First");
        String manifest = ShelfChapterRepository.manifest(List.of(item.sha256()));
        repository.declareManifest(claim, 1, manifest);
        repository.declareManifest(claim, 1, manifest);
        repository.stage(claim, List.of(item));
        repository.stage(claim, List.of(staged(claim, 0, "First")));
        assertThat(count("shelf_book_catalog_projection_staging")).isEqualTo(1);
        assertThatThrownBy(() -> repository.stage(claim, List.of(staged(claim, 0, "Changed"))))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThatThrownBy(() -> repository.declareManifest(claim, 1, "b".repeat(64)))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRefreshChangedCatalogWithNewRoundWhileKeepingStableChapterIdentity() {
        service.ensure(41, shelfId, "request");
        service.prepareOne("worker");
        var original = service.catalog(41, shelfId, 1, null);
        UUID chapter = original.items().getFirst().chapterId();
        when(runtime.catalog(any(ReaderRuntimeInvocation.class), any(SourceExecutionSnapshot.class), anyString()))
                .thenReturn(catalog("Renamed first", "Second", "New third"));
        assertThatThrownBy(() -> service.content(41, shelfId, chapter)).isInstanceOfSatisfying(ChapterAdaptationException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CHAPTER_CATALOG_STALE));
        var preparing = service.ensure(41, shelfId, "refresh");
        assertThat(preparing.bindingRevision()).isEqualTo(original.bindingRevision());
        service.prepareOne("worker");
        var refreshed = service.catalog(41, shelfId, 10, null);
        assertThat(refreshed.items()).hasSize(3);
        assertThat(refreshed.items().getFirst().chapterId()).isEqualTo(chapter);
        assertThat(refreshed.items().getFirst().title()).isEqualTo("Renamed first");
        assertThat(refreshed.catalogRevision()).isGreaterThan(original.catalogRevision());
        assertThat(count("shelf_book_preparation_dispatch")).isEqualTo(2);
        assertThatThrownBy(() -> service.catalog(41, shelfId, 1, original.nextCursor()))
                .isInstanceOf(ChapterAdaptationException.class);
    }

    @Test
    void shouldRebindNewSourceVersionAndFenceOldResult() throws Exception {
        var old = initialClaim();
        insertSourceVersion(2);
        jdbc.update("UPDATE book_source SET current_version = 2 WHERE id = ?", sourceId.toString());
        var rebound = service.ensure(41, shelfId, "new-version");
        assertThat(rebound.bindingRevision()).isEqualTo(2);
        assertThatThrownBy(() -> repository.declareManifest(old, 1, "a".repeat(64))).isInstanceOf(ChapterAdaptationException.class);
        assertThat(service.prepareOne("new-worker")).isTrue();
        assertThat(service.capability(41, shelfId).status()).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT DISTINCT binding_revision FROM shelf_book_source_locator")).hasSize(2);
    }

    @Test
    void shouldRejectMetadataChangesOrRemovalBeforePublishingAndBeforeContentWriteback() {
        var claim = initialClaim();
        jdbc.update("UPDATE shelf_book SET version = version + 1 WHERE id = ?", shelfId.toString());
        assertThatThrownBy(() -> repository.declareManifest(claim, 1, "a".repeat(64)))
                .isInstanceOf(ChapterAdaptationException.class);
        repository.fail(claim, ErrorCode.CHAPTER_SOURCE_CHANGED);
        service.ensure(41, shelfId, "updated");
        service.prepareOne("worker");
        var chapter = service.catalog(41, shelfId, 10, null).items().getFirst().chapterId();
        var scope = repository.readScope(41, shelfId, chapter);
        jdbc.update("UPDATE shelf_book SET deleted = TRUE WHERE id = ?", shelfId.toString());
        assertThatThrownBy(() -> repository.recordContentHash(scope, "a".repeat(64)))
                .isInstanceOf(ChapterAdaptationException.class);
        assertThat(repository.claim("worker")).isEmpty();
    }

    @Test
    void shouldBoundRetriesAndKeepUnavailableRuntimeOutOfPublicCatalog() {
        service.ensure(41, shelfId, "request");
        when(runtime.catalog(any(ReaderRuntimeInvocation.class), any(SourceExecutionSnapshot.class), anyString()))
                .thenThrow(new IllegalStateException("fixture runtime failure"));
        for (int index = 0; index < 3; index++) {
            assertThat(service.prepareOne("worker")).isTrue();
            clock.advance(10);
        }
        assertThat(service.capability(41, shelfId).status()).isEqualTo("BROKEN");
        assertThat(count("shelf_book_chapter")).isZero();
        assertThat(repository.claim("worker")).isEmpty();
    }

    @Test
    void shouldMergeConcurrentEnsureAndEnforceOwnerPreparationCapacity() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return service.ensure(41, shelfId, "first"); });
            var second = executor.submit(() -> { start.await(); return service.ensure(41, shelfId, "second"); });
            start.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(count("shelf_book_preparation_dispatch")).isEqualTo(1);
        UUID second = duplicateShelf();
        UUID third = duplicateShelf();
        service.ensure(41, second, "second-book");
        assertThatThrownBy(() -> service.ensure(41, third, "third-book"))
                .isInstanceOfSatisfying(ChapterAdaptationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ADAPTATION_CAPACITY_EXCEEDED));
        assertThat(count("shelf_book_preparation_dispatch")).isEqualTo(2);
    }

    private UUID duplicateShelf() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at)
                SELECT ?, owner_id, ?, metadata_json, created_at, updated_at FROM shelf_book WHERE id = ?
                """, id.toString(), id.toString(), shelfId.toString());
        return id;
    }

    private ShelfChapterModels.Claim initialClaim() {
        service.ensure(41, shelfId, "request");
        return repository.claim("worker").orElseThrow();
    }

    private ShelfChapterModels.StagedChapter staged(ShelfChapterModels.Claim claim, int index, String title) {
        return new ShelfChapterModels.StagedChapter(index, title, "TEXT",
                cipher.seal(claim.scope("CHAPTER"), policy.validate(CHAPTER + index)));
    }

    private BookSourceRuntimeModels.Catalog catalog(String... titles) {
        var chapters = new java.util.ArrayList<BookSourceRuntimeModels.Chapter>();
        for (int index = 0; index < titles.length; index++) {
            chapters.add(new BookSourceRuntimeModels.Chapter(titles[index], CHAPTER + index, index));
        }
        return new BookSourceRuntimeModels.Catalog("Book", "Author", "", "", "", chapters);
    }

    private int count(String fixtureTable) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fixtureTable, Integer.class);
    }

    private void insertSourceVersion(int version) throws Exception {
        String snapshot = mapper.writeValueAsString(Map.of("bookSourceUrl", SOURCE, "ruleContent", Map.of("content", "v" + version)));
        jdbc.update("INSERT INTO book_source_version VALUES (?, ?, ?, ?, ?)", sourceId.toString(), version, snapshot,
                AdaptationText.sha256(snapshot), Timestamp.from(clock.instant()));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        /** 夹具固定使用 UTC。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** 夹具不改变时区。 */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回可控当前时间。 */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
