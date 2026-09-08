package com.yuyutian.mytools.task.executor.runtime;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Executor 本地执行日志健康检查。
 */
@Component("executionJournal")
public class ExecutionJournalHealthIndicator implements HealthIndicator {

    private final ExecutionReportJournal reportJournal;

    /**
     * 创建执行日志健康检查。
     *
     * @param reportJournal 执行结果持久日志
     */
    public ExecutionJournalHealthIndicator(ExecutionReportJournal reportJournal) {
        this.reportJournal = reportJournal;
    }

    /**
     * 校验本地日志是否可读取。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        try {
            ExecutionReportJournal.JournalStatus status = reportJournal.status();
            reportJournal.validate();
            Health.Builder health = status.pendingReports() > 0
                    ? Health.status(Status.OUT_OF_SERVICE) : Health.up();
            return withStatusDetails(health, status).build();
        } catch (IOException exception) {
            try {
                ExecutionReportJournal.JournalStatus status = reportJournal.status();
                String error = status.diagnosticReports() > 0
                        ? "Execution journal requires manual diagnosis"
                        : "Execution journal is unreadable";
                return withStatusDetails(Health.down().withDetail("error", error), status).build();
            } catch (IOException ignored) {
                return Health.down().withDetail("error", "Execution journal is unreadable").build();
            }
        }
    }

    private Health.Builder withStatusDetails(Health.Builder health,
                                             ExecutionReportJournal.JournalStatus status) {
        health.withDetail("pendingReports", status.pendingReports());
        health.withDetail("acknowledgedReports", status.acknowledgedReports());
        health.withDetail("diagnosticReports", status.diagnosticReports());
        health.withDetail("diagnosticErrorCodes", status.diagnosticErrorCodes());
        if (status.earliestRetryAt() != null) {
            health.withDetail("earliestRetryAt", status.earliestRetryAt().toString());
        }
        return health;
    }
}
