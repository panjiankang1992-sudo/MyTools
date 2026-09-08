package com.yuyutian.mytools.task.executor.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionJournalMetricsTest {

    @Test
    void shouldExposeLowCardinalityJournalStateWithoutPayload() throws Exception {
        ExecutionReportJournal journal = mock(ExecutionReportJournal.class);
        when(journal.status()).thenReturn(new ExecutionReportJournal.JournalStatus(
                2, 4, 1, Instant.now().plusSeconds(30), Map.of("REPORT_CONFLICT", 1)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ExecutionJournalMetrics(journal).bindTo(registry);

        assertEquals(1, gauge(registry, "task.executor.journal.readable"));
        assertEquals(2, gauge(registry, "task.executor.journal.pending"));
        assertEquals(1, gauge(registry, "task.executor.journal.diagnostic"));
        assertTrue(gauge(registry, "task.executor.journal.retry.delay.seconds") <= 30);
    }

    @Test
    void shouldExposeUnreadableJournalWithoutReportingFalseZeroState() throws Exception {
        ExecutionReportJournal journal = mock(ExecutionReportJournal.class);
        when(journal.status()).thenThrow(new IOException("unreadable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ExecutionJournalMetrics(journal).bindTo(registry);

        assertEquals(0, gauge(registry, "task.executor.journal.readable"));
        assertTrue(Double.isNaN(gauge(registry, "task.executor.journal.pending")));
    }

    @Test
    void shouldExportPrometheusMetricNamesUsedByAlertRules() throws Exception {
        ExecutionReportJournal journal = mock(ExecutionReportJournal.class);
        when(journal.status()).thenReturn(new ExecutionReportJournal.JournalStatus(
                1, 0, 0, null, Map.of()));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new ExecutionJournalMetrics(journal).bindTo(registry);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("task_executor_journal_readable 1.0"));
        assertTrue(scrape.contains("task_executor_journal_pending 1.0"));
        assertTrue(scrape.contains("task_executor_journal_diagnostic 0.0"));
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
