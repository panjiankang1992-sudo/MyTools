package com.yuyutian.mytools.task.scheduler.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRuntimeMonitorTest {

    @Test
    void shouldReadQueueAndExecutionCounts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(7L, 3L, 2L);
        TaskRuntimeMonitor monitor = new TaskRuntimeMonitor(jdbcTemplate);

        assertEquals(7, monitor.queueDepth());
        assertEquals(3, monitor.runningExecutions());
        assertEquals(2, monitor.leaseLostExecutions());
    }

    @Test
    void shouldReturnZeroForEmptyAggregateCounts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
        TaskRuntimeMonitor monitor = new TaskRuntimeMonitor(jdbcTemplate);

        assertEquals(0, monitor.queueDepth());
    }

    @Test
    void shouldMeasureOldestQueueAge() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(Instant.now().minusSeconds(30)));
        TaskRuntimeMonitor monitor = new TaskRuntimeMonitor(jdbcTemplate);

        long age = monitor.oldestQueueWaitSeconds();

        assertTrue(age >= 29 && age <= 31);
    }

    @Test
    void shouldReturnZeroWhenQueueIsEmpty() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class))).thenReturn(null);
        TaskRuntimeMonitor monitor = new TaskRuntimeMonitor(jdbcTemplate);

        assertEquals(0, monitor.oldestQueueWaitSeconds());
    }

    @Test
    void shouldReadCompletedExecutionCountAndDuration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any())).thenReturn(4L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any())).thenReturn(2_500_000L);
        TaskRuntimeMonitor monitor = new TaskRuntimeMonitor(jdbcTemplate);

        assertEquals(4, monitor.completedExecutionCount("SUCCEEDED"));
        assertEquals(2.5D, monitor.completedExecutionSeconds("SUCCEEDED"));
    }
}
