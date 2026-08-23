package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.ChapterCacheBatchRequest;
import com.yuyutian.mytools.reader.model.CreateChapterPrefetchRequest;
import com.yuyutian.mytools.reader.model.DiscoveryRecord;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.repository.DiscoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class ChapterPrefetchServiceTest {

    @Autowired
    private ChapterPrefetchService service;

    @Autowired
    private DiscoveryRepository discoveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldPersistVerifiedChapterAndExposeOnlyUnexpiredCache() {
        long ownerId = 73L;
        Instant now = Instant.now();
        UUID discoveryId = UUID.randomUUID();
        discoveryRepository.insert(new DiscoveryRecord(discoveryId, ownerId, "prefetch-seed",
                "https://repository.example/sources.json", "RUNNING", null, 0, 0, 0, now, now));
        discoveryRepository.saveSources(discoveryId, List.of(Map.of(
                "bookSourceUrl", "https://chapter-source.example", "bookSourceName", "Chapters")));
        UUID sourceId = UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT id FROM book_source WHERE owner_id = ?", String.class, ownerId));
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        String bookUrl = "https://chapter-source.example/book/1";
        var request = new CreateChapterPrefetchRequest(ownerId, "prefetch-example", sourceId,
                bookUrl, List.of(2, 0, 2));

        var created = service.create(request);
        var duplicate = service.create(request);
        String content = "Chapter content";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        service.saveBatch(created.id(), new ChapterCacheBatchRequest(List.of(
                new ChapterCacheBatchRequest.Chapter(0, "Chapter One", "chapter-1", content,
                        DigestSupport.sha256(bytes), bytes.length, 3600L))));
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of()));

        var completed = service.get(created.id());
        var cached = service.cached(ownerId, sourceId, bookUrl, "chapter-1");

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(created.requestedCount()).isEqualTo(2);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.cachedCount()).isEqualTo(1);
        assertThat(cached.content()).isEqualTo(content);
        assertThat(cached.sourceId()).isEqualTo(sourceId);
    }
}
