package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateEbookImportRequest;
import com.yuyutian.mytools.reader.model.CreateManagedEbookImportRequest;
import com.yuyutian.mytools.reader.model.CatalogBatchRequest;
import com.yuyutian.mytools.reader.model.DiscoveryRecord;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class EbookImportServiceTest {

    @Autowired
    private EbookImportService importService;

    @Autowired
    private EbookCatalogWriteService catalogWriteService;

    @Autowired
    private DiscoveryRepository discoveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldUseImmutableSourceVersionAndRegisterImportedAsset() {
        long ownerId = 37L;
        UUID discoveryId = UUID.randomUUID();
        Instant now = Instant.now();
        discoveryRepository.insert(new DiscoveryRecord(discoveryId, ownerId, "ebook-seed",
                "https://repository.example/sources.json", "RUNNING", null, 0, 0, 0, now, now));
        discoveryRepository.saveSources(discoveryId, List.of(Map.of(
                "bookSourceUrl", "https://ebook-source.example", "bookSourceName", "Ebooks")));
        UUID sourceId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT id FROM book_source WHERE owner_id = ?", String.class, ownerId));
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var request = new CreateEbookImportRequest(ownerId, "ebook-example", sourceId,
                "https://ebook-source.example/book/1", "Fallback", "Fallback Author");

        var created = importService.create(request);
        var duplicate = importService.create(request);
        assertThatThrownBy(() -> importService.get(created.id(), ownerId + 1))
                .isInstanceOf(EbookImportNotFoundException.class);
        assertThatThrownBy(() -> importService.cancel(created.id(), ownerId + 1))
                .isInstanceOf(EbookImportNotFoundException.class);
        catalogWriteService.save(created.id(), new CatalogBatchRequest(true, List.of(
                new CatalogBatchRequest.CatalogEntry(0, "Chapter One", "text:0", 0L, 100L))));
        catalogWriteService.save(created.id(), new CatalogBatchRequest(false, List.of(
                new CatalogBatchRequest.CatalogEntry(1, "Chapter Two", "text:100", 100L, 200L))));
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of(
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "import_ebook", 1, "SUCCEEDED",
                        Map.of("title", "Example Book", "author", "Author", "chapterCount", 10,
                                "size", 1024L, "sha256", "a".repeat(64),
                                "storageUri", "storage://managed/ebooks/imports/example.txt")),
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "extract_metadata", 1,
                        "SUCCEEDED", Map.of("status", "READY", "parserName", "txt-utf8-v1",
                                "chapterCount", 10)),
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "build_catalog", 1,
                        "SUCCEEDED", Map.of("entryCount", 2)))));

        var completed = importService.get(created.id());
        var catalog = importService.catalog(created.id());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.sourceVersion()).isEqualTo(1);
        assertThat(completed.title()).isEqualTo("Example Book");
        assertThat(completed.chapterCount()).isEqualTo(10);
        assertThat(catalog.entries()).extracting("title").containsExactly("Chapter One", "Chapter Two");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ebook_asset WHERE import_request_id = ?",
                Integer.class, created.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT metadata_json FROM ebook_asset WHERE import_request_id = ?",
                String.class, created.id().toString())).contains("txt-utf8-v1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ebook_catalog_entry WHERE import_request_id = ?",
                Integer.class, created.id().toString())).isEqualTo(2);
        verify(schedulerClient).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }

    @Test
    void shouldCreateOwnerBoundManagedMediaImportWithFrozenIdentity() {
        long ownerId = 71L;
        UUID mediaItemId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var request = new CreateManagedEbookImportRequest(ownerId, "managed-import-1", mediaItemId,
                mediaAssetId, "Managed Book", "text/plain", 42,
                "a".repeat(64), true);

        var created = importService.createManaged(request);
        var duplicate = importService.createManaged(request);

        assertThat(created.id()).isEqualTo(duplicate.id());
        assertThat(created.sourceId()).isNotNull();
        assertThat(created.ebookAssetId()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM book_source WHERE owner_id = ? AND sync_key = ?",
                Integer.class, ownerId, "managed-media-library-v1")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT book_url FROM ebook_import_request WHERE id = ?", String.class,
                created.id().toString())).isEqualTo("media://" + mediaItemId);
        verify(schedulerClient).createTask(eq("reader_import_managed_ebook"),
                eq("reader_import_managed_ebook:" + created.id() + ":v1"), eq("READER_MANAGED_EBOOK_IMPORT"),
                eq(created.id()), eq(45), anyMap());
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of(
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "import_ebook", 1, "SUCCEEDED",
                        Map.of("title", "Managed Book", "author", "", "chapterCount", 2,
                                "size", 42L, "sha256", "a".repeat(64),
                                "storageUri", "storage://managed/ebooks/managed-book.txt")),
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "extract_metadata", 1,
                        "SUCCEEDED", Map.of("status", "READY", "parserName", "txt-utf8-v1")))));
        var completed = importService.get(created.id(), ownerId);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.ebookAssetId()).isNotNull();
        catalogWriteService.save(created.id(), new CatalogBatchRequest(true, List.of(
                new CatalogBatchRequest.CatalogEntry(0, "Chapter One", "text:0", 0L, 100L),
                new CatalogBatchRequest.CatalogEntry(1, "Chapter Two", "text:100", 100L, 200L))));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chapter_count FROM ebook_import_request WHERE id = ?", Integer.class,
                created.id().toString())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chapter_count FROM ebook_asset WHERE import_request_id = ?", Integer.class,
                created.id().toString())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT metadata_json FROM ebook_asset WHERE import_request_id = ?",
                String.class, created.id().toString())).contains(mediaItemId.toString(), mediaAssetId.toString());
        var epub = importService.createManaged(new CreateManagedEbookImportRequest(ownerId,
                "managed-import-2", UUID.randomUUID(), UUID.randomUUID(), "Managed Epub", "application/epub+zip", 42,
                "b".repeat(64), true));
        assertThat(epub.status()).isEqualTo("QUEUED");
    }

    @Test
    void shouldRegisterManagedEpubAssetWithItsFrozenFormat() {
        long ownerId = 72L;
        UUID mediaItemId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var created = importService.createManaged(new CreateManagedEbookImportRequest(ownerId, "managed-epub-1",
                mediaItemId, mediaAssetId, "Managed Epub", "application/epub+zip", 42,
                "b".repeat(64), true));
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of(
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "import_ebook", 1, "SUCCEEDED",
                        Map.of("title", "Managed Epub", "author", "", "format", "EPUB", "chapterCount", 2,
                                "size", 42L, "sha256", "b".repeat(64),
                                "storageUri", "storage://managed/ebooks/managed-book.epub")),
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "extract_metadata", 1,
                        "SUCCEEDED", Map.of("status", "READY", "parserName", "epub-opf-v1")))));

        var completed = importService.get(created.id(), ownerId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT format FROM ebook_asset WHERE import_request_id = ?", String.class,
                created.id().toString())).isEqualTo("EPUB");
    }
}
