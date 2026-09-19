package com.yuyutian.mytools.reader.repository.adaptation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实迁移建立隔离 H2 数据库，生产 MySQL 方言另由集成门禁验证。 */
class ShelfChapterBindingMigrationTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-09-10T00:00:00Z"));
    private static final String SHA = "a".repeat(64);
    private static JdbcTemplate jdbc;
    private String shelfId;
    private String sourceId;
    private String bindingId;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:shelf_binding_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void createScope() {
        shelfId = UUID.randomUUID().toString();
        sourceId = UUID.randomUUID().toString();
        bindingId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO shelf_book (id, owner_id, book_key, metadata_json, created_at, updated_at)
                VALUES (?, 41, ?, '{}', ?, ?)
                """, shelfId, shelfId, NOW, NOW);
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version,
                                         created_at, updated_at)
                VALUES (?, 41, ?, 'Source', 'https://example.invalid', TRUE, 2, ?, ?)
                """, sourceId, sourceId, NOW, NOW);
        for (int version = 1; version <= 2; version++) {
            jdbc.update("""
                    INSERT INTO book_source_version (book_source_id, version, snapshot_json, content_sha256, created_at)
                    VALUES (?, ?, '{}', ?, ?)
                    """, sourceId, version, SHA, NOW);
        }
        insertBinding(41, shelfId, sourceId, 1);
    }

    @Test
    void shouldBindExactOldSourceVersionAndRejectMissingVersion() {
        assertThat(jdbc.queryForObject("SELECT source_version FROM shelf_book_content_binding WHERE id = ?",
                Integer.class, bindingId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET source_version = 3 WHERE id = ?", bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldReadRequestedSourceSnapshotAddressWithoutCurrentVersionFallback() {
        jdbc.update("""
                UPDATE book_source_version SET snapshot_json = ? WHERE book_source_id = ? AND version = 1
                """, "{\"bookSourceUrl\":\"https://old.example.invalid\",\"ruleContent\":{\"content\":\"old-rule\"}}", sourceId);
        jdbc.update("""
                UPDATE book_source_version SET snapshot_json = ? WHERE book_source_id = ? AND version = 2
                """, "{\"bookSourceUrl\":\"https://new.example.invalid\",\"ruleContent\":{\"content\":\"new-rule\"}}", sourceId);
        var repository = new DiscoveryRepository(jdbc, new ObjectMapper());
        var exact = repository.findExecutionSnapshot(41L, UUID.fromString(sourceId), 1).orElseThrow();
        assertThat(exact.version()).isEqualTo(1);
        assertThat(exact.sourceUrl()).isEqualTo("https://old.example.invalid");
        assertThat(exact.snapshot().get("ruleContent")).isEqualTo(java.util.Map.of("content", "old-rule"));
        assertThat(repository.findExecutionSnapshot(41L, UUID.fromString(sourceId), 3)).isEmpty();
        assertThat(repository.findExecutionSnapshot(42L, UUID.fromString(sourceId), 1)).isEmpty();
        jdbc.update("UPDATE book_source SET enabled = FALSE WHERE id = ?", sourceId);
        assertThat(repository.findExecutionSnapshot(41L, UUID.fromString(sourceId), 1)).isEmpty();
    }

    @Test
    void shouldRejectCrossOwnerShelfSourceAndLocator() {
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET owner_id = 42 WHERE id = ?", bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLocator(42, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        String foreignSource = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO book_source (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 42, ?, 'Foreign', 'https://example.invalid', TRUE, 1, ?, ?)
                """, foreignSource, foreignSource, NOW, NOW);
        jdbc.update("INSERT INTO book_source_version VALUES (?, 1, '{}', ?, ?)", foreignSource, SHA, NOW);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET source_id = ? WHERE id = ?", foreignSource, bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceSourceXorRevisionAndActiveCatalogSeal() {
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET media_asset_id = ? WHERE id = ?",
                UUID.randomUUID().toString(), bindingId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET binding_revision = 0 WHERE id = ?", bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET status = 'ACTIVE' WHERE id = ?", bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE shelf_book_content_binding SET status = 'ACTIVE', catalog_revision = 1, catalog_sha256 = ? WHERE id = ?",
                SHA, bindingId);
    }

    @Test
    void shouldKeepSourceLocatorIdentityVersionedAndNonDeletable() {
        String locator = insertLocator(41, 1);
        assertThat(insertLocator(41, 2)).isNotEqualTo(locator);
        insertChapter(41, shelfId, locator, 1, 0, SHA);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shelf_book_source_locator WHERE id = ?", locator))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shelf_book WHERE id = ?", shelfId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM book_source_version WHERE book_source_id = ? AND version = 1", sourceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectChapterScopeVersionAndStableKeyConflicts() {
        String locator = insertLocator(41, 1);
        insertChapter(41, shelfId, locator, 1, 0, SHA);
        assertThatThrownBy(() -> insertChapter(42, shelfId, locator, 1, 1, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChapter(41, shelfId, locator, 2, 1, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChapter(41, shelfId, locator, 1, 1, SHA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChapter(41, shelfId, locator, 1, 50000, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRequireCompleteSourceManifestAndPairedLease() {
        String dispatch = insertDispatch();
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_preparation_dispatch SET status = 'SUCCEEDED' WHERE id = ?", dispatch))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_preparation_dispatch SET catalog_item_count = 1 WHERE id = ?", dispatch))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_preparation_dispatch SET claim_owner = 'worker' WHERE id = ?", dispatch))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE shelf_book_preparation_dispatch SET status = 'SUCCEEDED', catalog_item_count = 1,
                    catalog_canonicalization_version = 'catalog-v1', catalog_manifest_sha256 = ? WHERE id = ?
                """, SHA, dispatch);
    }

    @Test
    void shouldEnforceStagingDispatchOwnerRevisionAndOrdinal() {
        String dispatch = insertDispatch();
        insertStaging(dispatch, 41, 1, 0, SHA);
        assertThatThrownBy(() -> insertStaging(dispatch, 42, 1, 1, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertStaging(dispatch, 41, 2, 1, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertStaging(dispatch, 41, 1, 0, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertStaging(dispatch, 41, 1, 1, SHA))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shelf_book_preparation_dispatch WHERE id = ?", dispatch))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRequireManagedAssetAndCompleteProjectionSeal() {
        String projection = insertRemoteProjection();
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_text_projection SET status = 'SEALED' WHERE id = ?", projection))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_text_projection SET owner_id = 42 WHERE id = ?", projection))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE shelf_book_text_projection SET status = 'SEALED', chapter_count = 1,
                    manifest_sha256 = ?, sealed_at = ? WHERE id = ?
                """, SHA, NOW, projection);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_content_binding SET ebook_asset_id = NULL, status = 'ACTIVE',"
                + " catalog_revision = 1, catalog_sha256 = ? WHERE id = ?", SHA, bindingId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceProjectionChapterOwnerRevisionAndDeletionRestriction() {
        String projection = insertRemoteProjection();
        insertProjectionChapter(projection, 41, 1, 0);
        assertThatThrownBy(() -> insertProjectionChapter(projection, 42, 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProjectionChapter(projection, 41, 2, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM shelf_book_text_projection WHERE id = ?", projection))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shelf_book_text_projection_chapter SET codepoint_count = 0 WHERE projection_id = ?", projection))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String insertRemoteProjection() {
        String request = UUID.randomUUID().toString();
        String asset = UUID.randomUUID().toString();
        String media = UUID.randomUUID().toString();
        String projection = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ebook_import_request (id, owner_id, idempotency_key, source_id, source_version, book_url,
                    requested_title, storage_root, status, parameters_json, created_at, updated_at)
                VALUES (?, 41, ?, ?, 1, 'media-library://managed', 'Book', 'reader', 'SUCCEEDED', '{}', ?, ?)
                """, request, request, sourceId, NOW, NOW);
        jdbc.update("""
                INSERT INTO ebook_asset (id, import_request_id, owner_id, source_id, title, format, storage_uri,
                    size_bytes, content_sha256, chapter_count, metadata_json, created_at, updated_at)
                VALUES (?, ?, 41, ?, 'Book', 'TXT', 'managed://fixture', 16, ?, 1, '{}', ?, ?)
                """, asset, request, sourceId, SHA, NOW, NOW);
        jdbc.update("""
                UPDATE shelf_book_content_binding SET binding_type = 'EBOOK_ASSET', source_id = NULL,
                    source_version = NULL, source_book_key = NULL, media_item_id = ?, media_asset_id = ?,
                    media_content_sha256 = ?, ebook_asset_id = ? WHERE id = ?
                """, UUID.randomUUID().toString(), media, SHA, asset, bindingId);
        jdbc.update("""
                INSERT INTO shelf_book_text_projection (id, owner_id, shelf_book_id, binding_id, binding_revision,
                    media_asset_id, media_content_sha256, ebook_asset_id, projection_format_version, status, created_at)
                VALUES (?, 41, ?, ?, 1, ?, ?, ?, 'text-v1', 'BUILDING', ?)
                """, projection, shelfId, bindingId, media, SHA, asset, NOW);
        return projection;
    }

    private void insertProjectionChapter(String projection, long owner, long revision, int ordinal) {
        jdbc.update("""
                INSERT INTO shelf_book_text_projection_chapter (id, projection_id, owner_id, binding_id, binding_revision,
                    ordinal, stable_chapter_key_sha256, title, content_text, content_sha256, codepoint_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Chapter', 'Fixture text.', ?, 13, ?)
                """, UUID.randomUUID().toString(), projection, owner, bindingId, revision, ordinal, SHA, SHA, NOW);
    }

    private void insertBinding(long owner, String shelf, String source, int version) {
        jdbc.update("""
                INSERT INTO shelf_book_content_binding (id, owner_id, shelf_book_id, binding_type, source_id,
                    source_version, source_book_key, binding_revision, status, created_at, updated_at)
                VALUES (?, ?, ?, 'SOURCE_RUNTIME', ?, ?, ?, 1, 'PREPARING', ?, ?)
                """, bindingId, owner, shelf, source, version, SHA, NOW, NOW);
    }

    private String insertLocator(long owner, int version) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO shelf_book_source_locator (id, owner_id, binding_id, source_version, locator_scope, binding_revision,
                    locator_ciphertext, locator_sha256, allowed_scheme, allowed_host, created_at)
                VALUES (?, ?, ?, ?, 'CHAPTER', 1, 'encrypted-fixture', ?, 'https', 'example.invalid', ?)
                """, id, owner, bindingId, version, SHA, NOW);
        return id;
    }

    private void insertChapter(long owner, String shelf, String locator, int version, int index, String key) {
        jdbc.update("""
                INSERT INTO shelf_book_chapter (id, owner_id, shelf_book_id, content_binding_id, binding_revision,
                    catalog_revision, chapter_index, chapter_key_sha256, chapter_title, locator_kind,
                    source_locator_id, source_version, content_kind, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 1, ?, ?, 'Chapter', 'SOURCE_CATALOG_KEY', ?, ?, 'TEXT', TRUE, ?, ?)
                """, UUID.randomUUID().toString(), owner, shelf, bindingId, index, key, locator, version, NOW, NOW);
    }

    private String insertDispatch() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO shelf_book_preparation_dispatch (id, owner_id, binding_id, binding_revision, phase,
                    status, deterministic_request_id, invocation_id, next_dispatch_at, created_at, updated_at)
                VALUES (?, 41, ?, 1, 'SOURCE_CATALOG', 'PENDING_DISPATCH', ?, ?, ?, ?, ?)
                """, id, bindingId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), NOW, NOW, NOW);
        return id;
    }

    private void insertStaging(String dispatch, long owner, long revision, int ordinal, String key) {
        jdbc.update("""
                INSERT INTO shelf_book_catalog_projection_staging (id, owner_id, dispatch_id, binding_id,
                    binding_revision, ordinal, stable_chapter_key_sha256, title, content_kind,
                    locator_ciphertext, locator_sha256, item_sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Chapter', 'TEXT', 'encrypted-fixture', ?, ?, ?)
                """, UUID.randomUUID().toString(), owner, dispatch, bindingId, revision, ordinal, key, SHA, SHA, NOW);
    }
}
