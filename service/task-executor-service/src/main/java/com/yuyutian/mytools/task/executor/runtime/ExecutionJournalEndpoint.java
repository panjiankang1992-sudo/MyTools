package com.yuyutian.mytools.task.executor.runtime;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executor 本地 Journal 的受控人工诊断端点。
 */
@Component
@Endpoint(id = "executionjournal")
public class ExecutionJournalEndpoint {

    private final ExecutionReportJournal reportJournal;

    /**
     * 创建 Journal 运维端点。
     *
     * @param reportJournal 执行结果持久日志
     */
    public ExecutionJournalEndpoint(ExecutionReportJournal reportJournal) {
        this.reportJournal = reportJournal;
    }

    /**
     * 返回不包含执行载荷的 Journal 状态和人工诊断列表。
     *
     * @return 运维摘要
     * @throws IOException Journal 不可读取
     */
    @ReadOperation
    public Map<String, Object> status() throws IOException {
        return Map.of(
                "status", reportJournal.status(),
                "diagnosticReports", reportJournal.diagnosticReports(100));
    }

    /**
     * 按报告标识和预期错误码将人工诊断记录重新排队。
     *
     * @param reportId 报告标识
     * @param expectedErrorCode 操作员确认的错误码
     * @return 操作结果
     * @throws IOException 条件不匹配或 Journal 不可写
     */
    @WriteOperation
    public Map<String, Object> retry(@Selector String reportId, String expectedErrorCode) throws IOException {
        reportJournal.retryDiagnostic(UUID.fromString(reportId), expectedErrorCode);
        return Map.of("reportId", reportId, "state", "PENDING");
    }
}
