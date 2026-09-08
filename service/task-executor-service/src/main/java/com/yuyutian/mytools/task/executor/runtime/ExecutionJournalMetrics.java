package com.yuyutian.mytools.task.executor.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

/**
 * Executor 本地 Journal 低基数指标注册器。
 */
@Component
public class ExecutionJournalMetrics implements MeterBinder {

    private final ExecutionReportJournal journal;

    /**
     * 创建 Journal 指标注册器。
     *
     * @param journal 执行结果持久日志
     */
    public ExecutionJournalMetrics(ExecutionReportJournal journal) {
        this.journal = journal;
    }

    /**
     * 注册 Journal 可读性、待上报、人工诊断和重试延迟指标。
     *
     * @param registry 指标注册表
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("task.executor.journal.readable", journal, this::readable)
                .description("Whether the execution report journal can be read")
                .register(registry);
        registerStatusGauge(registry, "task.executor.journal.pending",
                "Number of execution reports awaiting acknowledgement",
                status -> status.pendingReports());
        registerStatusGauge(registry, "task.executor.journal.diagnostic",
                "Number of execution reports requiring manual diagnosis",
                status -> status.diagnosticReports());
        registerStatusGauge(registry, "task.executor.journal.retry.delay.seconds",
                "Seconds until the earliest deferred execution report retry",
                this::retryDelaySeconds);
    }

    private void registerStatusGauge(MeterRegistry registry, String name, String description,
                                     ToDoubleFunction<ExecutionReportJournal.JournalStatus> valueFunction) {
        Gauge.builder(name, journal, value -> statusValue(value, valueFunction))
                .description(description)
                .register(registry);
    }

    private double readable(ExecutionReportJournal value) {
        try {
            value.status();
            return 1;
        } catch (IOException exception) {
            return 0;
        }
    }

    private double statusValue(ExecutionReportJournal value,
                               ToDoubleFunction<ExecutionReportJournal.JournalStatus> valueFunction) {
        try {
            return valueFunction.applyAsDouble(value.status());
        } catch (IOException exception) {
            // 可读性指标负责告警，状态值不可读时不伪造为零。
            return Double.NaN;
        }
    }

    private double retryDelaySeconds(ExecutionReportJournal.JournalStatus status) {
        if (status.earliestRetryAt() == null) {
            return 0;
        }
        return Math.max(0, Duration.between(Instant.now(), status.earliestRetryAt()).toSeconds());
    }
}
