package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateLibraryRebuildRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reader_library_rebuild;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class LibraryRebuildServiceTest {

    @Autowired
    private LibraryRebuildService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldBuildFrozenGenerationAndPublishWithoutChangingReaderState() {
        long ownerId = 91L;
        Instant assetTime = Instant.now().minusSeconds(10);
        seedAsset(ownerId, "First", "a".repeat(64), assetTime);
        seedAsset(ownerId, "First Duplicate", "a".repeat(64), assetTime);
        seedAsset(ownerId, "Second", "b".repeat(64), assetTime);
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(UUID.randomUUID());
        CreateLibraryRebuildRequest request = new CreateLibraryRebuildRequest(ownerId, "rebuild-1",
                Instant.now().minusSeconds(1), 1);

        var created = service.create(request);
        var duplicate = service.create(request);
        var firstBatch = service.rebuildBatch(created.id());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(service.activeIndex(ownerId)).isEmpty();
        assertThat(firstBatch.done()).isFalse();
        assertThatThrownBy(() -> service.publish(created.id()))
                .isInstanceOf(LibraryRebuildConflictException.class);

        var batch = firstBatch;
        while (!batch.done()) {
            batch = service.rebuildBatch(created.id());
        }
        var published = service.publish(created.id());

        assertThat(published.status()).isEqualTo("SUCCEEDED");
        assertThat(published.indexedCount()).isEqualTo(2);
        assertThat(service.activeIndex(ownerId)).hasSize(2).extracting("contentSha256")
                .containsExactlyInAnyOrder("a".repeat(64), "b".repeat(64));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM library_index_generation WHERE active = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shelf_book", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reading_progress", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reader_marker", Integer.class)).isZero();
        verify(schedulerClient, times(1)).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }

    private void seedAsset(long ownerId, String title, String sha256, Instant createdAt) {
        UUID sourceId = source(ownerId, createdAt);
        UUID requestId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ebook_import_request
                    (id, owner_id, idempotency_key, source_id, source_version, book_url, requested_title,
                     requested_author, storage_root, status, task_instance_id, parameters_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, NULL, 'managed', 'SUCCEEDED', NULL, ?, ?, ?)
                """, requestId.toString(), ownerId, requestId.toString(), sourceId.toString(),
                "https://books.example/" + requestId, title, "{}", Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO ebook_asset
                    (id, import_request_id, owner_id, source_id, title, author, format, storage_uri, size_bytes,
                     content_sha256, chapter_count, metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'Author', 'EPUB', ?, 100, ?, 1, ?, ?, ?)
                """, assetId.toString(), requestId.toString(), ownerId, sourceId.toString(), title,
                "storage://managed/" + assetId, sha256, "{}", Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private UUID source(long ownerId, Instant createdAt) {
        String existing = jdbcTemplate.query("SELECT id FROM book_source WHERE owner_id = ?",
                resultSet -> resultSet.next() ? resultSet.getString(1) : null, ownerId);
        if (existing != null) {
            return UUID.fromString(existing);
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO book_source
                    (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, ?, ?, 'Library Test', ?, TRUE, 1, ?, ?)
                """, id.toString(), ownerId, id.toString(), "https://source.example/" + id,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        jdbcTemplate.update("""
                INSERT INTO book_source_version
                    (book_source_id, version, snapshot_json, content_sha256, created_at)
                VALUES (?, 1, ?, ?, ?)
                """, id.toString(), "{}", "c".repeat(64), Timestamp.from(createdAt));
        return id;
    }
}
