package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateCacheMaintenanceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节缓存维护服务集成测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reader_cache_maintenance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CacheMaintenanceServiceTest {

    @Autowired
    private CacheMaintenanceService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldDeleteExpiredCacheInBoundedIdempotentTask() {
        UUID sourceId = insertSource(true, 1);
        UUID cacheId = insertCache(sourceId, 1, Instant.now().minus(1, ChronoUnit.DAYS));
        insertPrefetchLink(sourceId, cacheId);
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(taskId);
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var request = new CreateCacheMaintenanceRequest("expired-2026-08-23", "EXPIRED", cutoff, 10);

        var created = service.create(request);
        var replay = service.create(request);
        var firstBatch = service.deleteBatch(created.id());
        var emptyBatch = service.deleteBatch(created.id());
        var finished = service.finish(created.id(), "SUCCEEDED", null);

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(created.taskId()).isEqualTo(taskId);
        assertThat(firstBatch.deleted()).isEqualTo(1);
        assertThat(emptyBatch.deleted()).isZero();
        assertThat(finished.deletedCount()).isEqualTo(1);
        assertThat(finished.status()).isEqualTo("SUCCEEDED");
        verify(schedulerClient, times(1)).createTask(anyString(), anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    void shouldDeleteCacheFromDisabledOrChangedSource() {
        UUID sourceId = insertSource(false, 2);
        insertCache(sourceId, 1, Instant.now().plus(1, ChronoUnit.DAYS));
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(UUID.randomUUID());
        var request = new CreateCacheMaintenanceRequest("stale-2026-08-23", "STALE_SOURCE",
                Instant.now().truncatedTo(ChronoUnit.MICROS), 1);

        var created = service.create(request);
        var batch = service.deleteBatch(created.id());

        assertThat(batch.deleted()).isEqualTo(1);
    }

    private UUID insertSource(boolean enabled, int version) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO book_source
                    (id, owner_id, sync_key, name, source_url, enabled, current_version, created_at, updated_at)
                VALUES (?, 1, ?, 'source', 'https://source.test', ?, ?, ?, ?)
                """, id.toString(), id.toString(), enabled, version, Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private UUID insertCache(UUID sourceId, int sourceVersion, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now().minusSeconds(10);
        jdbcTemplate.update("""
                INSERT INTO chapter_cache
                    (id, owner_id, source_id, source_version, book_key, book_url, chapter_key, chapter_url,
                     chapter_index, chapter_title, content_text, content_sha256, size_bytes, expires_at,
                     created_at, updated_at)
                VALUES (?, 1, ?, ?, ?, 'https://book.test', ?, 'https://book.test/1', 1, 'chapter',
                        'content', ?, 7, ?, ?, ?)
                """, id.toString(), sourceId.toString(), sourceVersion, "a".repeat(64), "b".repeat(64),
                "c".repeat(64), Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private void insertPrefetchLink(UUID sourceId, UUID cacheId) {
        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO chapter_prefetch_request
                    (id, owner_id, idempotency_key, source_id, source_version, book_url, status,
                     task_instance_id, parameters_json, requested_count, cached_count, created_at, updated_at)
                VALUES (?, 1, ?, ?, 1, 'https://book.test', 'SUCCEEDED', NULL, '{}', 1, 1, ?, ?)
                """, requestId.toString(), requestId.toString(), sourceId.toString(),
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO chapter_prefetch_cache_link (prefetch_request_id, chapter_cache_id, created_at)
                VALUES (?, ?, ?)
                """, requestId.toString(), cacheId.toString(), Timestamp.from(now));
    }
}
