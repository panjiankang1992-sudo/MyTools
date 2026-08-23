package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateSearchRequest;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.model.SearchMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class ReaderSearchServiceTest {

    @Autowired
    private ReaderSearchService searchService;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldCreateIdempotentlyAndAggregatePartialShardResults() {
        UUID taskId = UUID.randomUUID();
        UUID successfulExecution = UUID.randomUUID();
        UUID failedExecution = UUID.randomUUID();
        when(schedulerClient.createSearchTask(anyString(), any(), anyMap())).thenReturn(taskId);
        var request = new CreateSearchRequest(7L, "search-example", "Example", SearchMode.FUZZY, 1,
                List.of(new CreateSearchRequest.SourceSnapshot(
                        "legacy-1", "Source", "https://source.example", 1, Map.of("enabled", true))));

        var created = searchService.create(request);
        var duplicate = searchService.create(request);
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "FAILED", List.of(
                new SchedulerResult.StepResult(successfulExecution, 0, 2, "search_sources", 1, "SUCCEEDED",
                        Map.of("results", List.of(
                                Map.of("name", "Example Book", "sourceId", "legacy-1"),
                                Map.of("name", " Example   Book ", "sourceId", "legacy-2")))),
                new SchedulerResult.StepResult(failedExecution, 1, 2, "search_sources", 1, "FAILED", Map.of()))));

        var result = searchService.get(created.id());

        assertThat(duplicate.id()).isEqualTo(created.id());
        assertThat(result.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(result.completedShards()).isEqualTo(1);
        assertThat(result.failedShards()).isEqualTo(1);
        assertThat(result.totalShards()).isEqualTo(2);
        assertThat(result.results()).hasSize(1);
        verify(schedulerClient).createSearchTask(anyString(), any(), anyMap());
    }
}
