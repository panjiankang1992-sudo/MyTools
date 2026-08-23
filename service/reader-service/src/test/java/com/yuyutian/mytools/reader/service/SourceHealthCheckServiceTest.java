package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.reader.model.CreateHealthCheckRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
class SourceHealthCheckServiceTest {

    @Autowired
    private SourceHealthCheckService healthCheckService;

    @Autowired
    private DiscoveryRepository discoveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @Test
    void shouldAggregateShardsWithoutDisablingUnhealthySource() {
        long ownerId = 27L;
        UUID discoveryId = UUID.randomUUID();
        Instant now = Instant.now();
        discoveryRepository.insert(new DiscoveryRecord(discoveryId, ownerId, "health-seed",
                "https://repository.example/sources.json", "RUNNING", null, 0, 0, 0, now, now));
        discoveryRepository.saveSources(discoveryId, List.of(
                Map.of("bookSourceUrl", "https://healthy.example", "bookSourceName", "Healthy"),
                Map.of("bookSourceUrl", "https://unhealthy.example", "bookSourceName", "Unhealthy")));
        List<String> sourceIds = jdbcTemplate.queryForList(
                "SELECT id FROM book_source WHERE owner_id = ? ORDER BY source_url", String.class, ownerId);
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createTask(anyString(), anyString(), anyString(), any(), anyInt(), anyMap()))
                .thenReturn(taskId);
        var created = healthCheckService.create(new CreateHealthCheckRequest(
                ownerId, "daily-health", "test"));
        when(schedulerClient.getResults(taskId)).thenReturn(new SchedulerResult(taskId, "SUCCEEDED", List.of(
                new SchedulerResult.StepResult(UUID.randomUUID(), 0, 2, "check_sources", 1, "SUCCEEDED",
                        Map.of("results", List.of(Map.of("sourceId", sourceIds.get(0),
                                "status", "HEALTHY", "latencyMillis", 12)))),
                new SchedulerResult.StepResult(UUID.randomUUID(), 1, 2, "check_sources", 1, "SUCCEEDED",
                        Map.of("results", List.of(Map.of("sourceId", sourceIds.get(1),
                                "status", "UNHEALTHY", "latencyMillis", 250,
                                "errorCode", "TimeoutError")))))));

        var completed = healthCheckService.get(created.id());

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.checked()).isEqualTo(2);
        assertThat(completed.healthy()).isEqualTo(1);
        assertThat(completed.unhealthy()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM book_source WHERE owner_id = ? AND enabled = TRUE", Integer.class, ownerId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM book_source_health_result WHERE health_check_id = ?",
                Integer.class, created.id().toString())).isEqualTo(2);
    }
}
