package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateSearchRequest;
import com.yuyutian.mytools.reader.model.SchedulerResult;
import com.yuyutian.mytools.reader.model.SearchMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        var request = new CreateSearchRequest(7L, "search-example", "Example", SearchMode.FUZZY, 1, List.of(),
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

    @Test
    void shouldScopeIdempotencyAndSearchAccessByOwner() {
        UUID firstTask=UUID.randomUUID(),secondTask=UUID.randomUUID();
        when(schedulerClient.createSearchTask(anyString(),any(),anyMap())).thenReturn(firstTask,secondTask);
        var sources=List.of(new CreateSearchRequest.SourceSnapshot("source","Source","https://source.example",1,Map.of()));
        var first=searchService.create(new CreateSearchRequest(17L,"shared-key","Book",SearchMode.EXACT,1,List.of(),sources));
        var second=searchService.create(new CreateSearchRequest(18L,"shared-key","Book",SearchMode.EXACT,1,List.of(),sources));

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThatThrownBy(()->searchService.get(first.id(),18L)).isInstanceOf(SearchNotFoundException.class);
        assertThatThrownBy(()->searchService.cancel(first.id(),18L)).isInstanceOf(SearchNotFoundException.class);
    }

    @Test
    void shouldFreezeProbeTermsIntoSchedulerParameters() {
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createSearchTask(anyString(), any(), anyMap())).thenReturn(taskId);
        var sources = List.of(new CreateSearchRequest.SourceSnapshot(
                "source", "Source", "https://source.example", 1, Map.of()));

        searchService.create(new CreateSearchRequest(7L, "probe-key", "plot clue", SearchMode.PROBE,
                1, List.of("hero", "lost prince"), sources));

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> parameters =
                ArgumentCaptor.forClass(Map.class);
        verify(schedulerClient).createSearchTask(anyString(), any(), parameters.capture());
        assertThat(parameters.getValue().get("mode")).isEqualTo("PROBE");
        assertThat(parameters.getValue().get("searchTerms")).isEqualTo(List.of("hero", "lost prince"));
    }
}
