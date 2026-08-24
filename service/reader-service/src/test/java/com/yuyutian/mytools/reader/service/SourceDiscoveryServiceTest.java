package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateDiscoveryRequest;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.model.SourceIngestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class SourceDiscoveryServiceTest {

    @Autowired
    private SourceDiscoveryService discoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldPersistVersionedSourcesAndSynchronizeTaskSummary() {
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var request = new CreateDiscoveryRequest(17L, "repository-example",
                "https://repository.example/sources.json");

        var created = discoveryService.create(request);
        var duplicate = discoveryService.create(request);
        assertThatThrownBy(() -> discoveryService.get(created.id(), 18L))
                .isInstanceOf(DiscoveryNotFoundException.class);
        assertThatThrownBy(() -> discoveryService.cancel(created.id(), 18L))
                .isInstanceOf(DiscoveryNotFoundException.class);
        Map<String, Object> source = Map.of(
                "bookSourceUrl", "https://books.example", "bookSourceName", "Books",
                "ruleSearch", Map.of("bookList", ".book"));
        var firstBatch = discoveryService.ingest(created.id(), new SourceIngestRequest(List.of(source)));
        var repeatedBatch = discoveryService.ingest(created.id(), new SourceIngestRequest(List.of(source)));
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of(
                new SchedulerResult.StepResult(UUID.randomUUID(), null, null, "discover_sources", 1,
                        "SUCCEEDED", Map.of("processed", 1, "saved", 1, "rejected", 0)))));

        var completed = discoveryService.get(created.id());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(firstBatch.saved()).isEqualTo(1);
        assertThat(repeatedBatch.saved()).isEqualTo(1);
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.saved()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM book_source WHERE owner_id = 17", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM book_source_version bsv
                JOIN book_source bs ON bs.id = bsv.book_source_id WHERE bs.owner_id = 17
                """, Integer.class)).isEqualTo(1);
        verify(schedulerClient).createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap());
    }
}
