package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionCompletion;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.client.SchedulerClientException;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 SQLite WAL 的执行结果持久上报日志。
 */
@Component
public class ExecutionReportJournal implements AutoCloseable {

    private static final String PENDING = "PENDING";
    private static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String DIAGNOSTIC = "DIAGNOSTIC";

    private final ObjectMapper objectMapper;
    private final Path journalRoot;
    private final Path databasePath;
    private final Path ownershipLockPath;
    private final Path activeRoot;
    private final Path archiveRoot;
    private final Path diagnosticRoot;
    private final Path legacyImportedRoot;
    private final Path workRoot;
    private final long successfulWorkRetentionSeconds;
    private final MeterRegistry meterRegistry;
    private final ExecutionLogArchiver logArchiver;
    private volatile IOException initializationFailure;
    private volatile boolean closed;
    private final Object initializationMonitor = new Object();
    private final ReentrantLock replayLock = new ReentrantLock();
    private final ReentrantLock cleanupLock = new ReentrantLock();
    private FileChannel ownershipChannel;
    private FileLock ownershipLock;

    /**
     * 创建执行结果 SQLite WAL。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     */
    public ExecutionReportJournal(ExecutorProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    /**
     * 创建带上报重试指标且不启用远端日志归档的兼容 Journal。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     * @param meterRegistry 指标注册器
     */
    public ExecutionReportJournal(ExecutorProperties properties, ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this(properties, objectMapper, meterRegistry, null);
    }

    /**
     * 创建带上报重试指标的执行结果 SQLite WAL。
     *
     * @param properties 执行节点配置
     * @param objectMapper JSON 映射器
     * @param meterRegistry 指标注册器
     */
    @Autowired
    public ExecutionReportJournal(ExecutorProperties properties, ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry, ExecutionLogArchiver logArchiver) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.meterRegistry = meterRegistry;
        this.logArchiver = logArchiver;
        this.workRoot = properties.workRoot().toAbsolutePath().normalize();
        this.successfulWorkRetentionSeconds = properties.successfulWorkRetentionSeconds();
        this.journalRoot = workRoot.resolveSibling(workRoot.getFileName() + "-report-journal");
        this.databasePath = journalRoot.resolve("execution-report.db");
        this.ownershipLockPath = journalRoot.resolve("executor-owner.lock");
        this.activeRoot = journalRoot.resolve("active");
        this.archiveRoot = journalRoot.resolve("archive");
        this.diagnosticRoot = journalRoot.resolve("diagnostic");
        this.legacyImportedRoot = journalRoot.resolve("legacy-imported");
        IOException failure = null;
        try {
            initialize();
            importLegacyRecords();
            // 启动期间做一次完整校验，阻止损坏的历史 WAL 在节点注册后才暴露。
            validate();
        } catch (IOException exception) {
            failure = exception;
            releaseOwnershipLockAfterFailure();
        }
        this.initializationFailure = failure;
    }

    /**
     * 记录已领取且尚未完成确认的执行。
     *
     * @param task 已领取任务
     * @throws IOException 持久化失败
     */
    public void recordClaim(ClaimedTask task) throws IOException {
        upsertExecution(new ActiveExecution(task, "CLAIMED", "RETAINED", null, Instant.now()));
    }

    /**
     * 持久化步骤上报并返回日志标识。
     *
     * @param task 已领取任务
     * @param step 步骤
     * @param attempt 尝试次数
     * @param status 状态
     * @param exitCode 退出码
     * @param result 结果
     * @param errorCode 错误码
     * @param errorMessage 错误摘要
     * @return 日志标识
     * @throws IOException 持久化失败
     */
    public UUID persistStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                            Map<String, Object> result, String errorCode, String errorMessage) throws IOException {
        return persistStep(task, step, attempt, status, exitCode, result, errorCode, errorMessage, Map.of());
    }

    /**
     * 持久化带日志索引的步骤上报并返回日志标识。
     */
    public UUID persistStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                            Map<String, Object> result, String errorCode, String errorMessage,
                            Map<String, Object> logIndex) throws IOException {
        UUID id = stableId("step", task.executionId(), step.stepDefinitionId(), attempt);
        persist(new PendingReport(id, "STEP", task, step, attempt, status, exitCode,
                result, errorCode, errorMessage, logIndex, null, false, null));
        return id;
    }

    /**
     * 持久化执行完成上报并返回日志标识。
     *
     * @param task 已领取任务
     * @param status 最终状态
     * @return 日志标识
     * @throws IOException 持久化失败
     */
    public UUID persistCompletion(ClaimedTask task, String status) throws IOException {
        return persistCompletion(task, ExecutionCompletion.withoutCompensation(status));
    }

    /**
     * 持久化含补偿结果的执行完成上报并返回日志标识。
     *
     * @param task 已领取任务
     * @param completion 执行终态及补偿结果
     * @return 日志标识
     * @throws IOException 持久化失败
     */
    public UUID persistCompletion(ClaimedTask task, ExecutionCompletion completion) throws IOException {
        UUID id = stableId("complete", task.executionId(), completion.status(), completion.compensationStatus(),
                completion.compensationRequired(), completion.compensationErrorCode());
        persist(new PendingReport(id, "COMPLETE", task, null, 0, completion.status(), null, Map.of(), null, null,
                Map.of(), completion.compensationStatus(), completion.compensationRequired(),
                completion.compensationErrorCode()));
        return id;
    }

    /**
     * 确认上报成功。
     *
     * @param id 日志标识
     * @throws IOException 持久化失败
     */
    public void acknowledge(UUID id) throws IOException {
        executeUpdate("UPDATE journal_report SET state = ?, updated_at = ? WHERE id = ? AND state = ?",
                ACKNOWLEDGED, Instant.now().toString(), id.toString(), PENDING);
    }

    /**
     * 记录最终完成上报已确认。
     *
     * @param task 已领取任务
     * @param finalStatus 最终状态
     * @throws IOException 持久化失败
     */
    public void acknowledgeExecution(ClaimedTask task, String finalStatus) throws IOException {
        upsertExecution(new ActiveExecution(task, "COMPLETION_ACKNOWLEDGED", "RETAINED",
                finalStatus, Instant.now()));
    }

    /**
     * 清理已确认成功执行的工作目录。
     *
     * @return 本次清理的执行目录数量
     * @throws IOException 读取 WAL 或删除目录失败
     */
    public int cleanupAcknowledgedWorkDirectories() throws IOException {
        cleanupLock.lock();
        try {
            return cleanupAcknowledgedWorkDirectoriesLocked();
        } finally {
            cleanupLock.unlock();
        }
    }

    private int cleanupAcknowledgedWorkDirectoriesLocked() throws IOException {
        List<ActiveExecution> executions = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json FROM journal_execution
                WHERE state = 'COMPLETION_ACKNOWLEDGED' AND work_directory_state = 'RETAINED'
                  AND final_status = 'SUCCEEDED' AND updated_at <= ?
                """)) {
            statement.setString(1, Instant.now().minusSeconds(successfulWorkRetentionSeconds).toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    executions.add(read(resultSet.getString(1), ActiveExecution.class));
                }
            }
        } catch (SQLException exception) {
            throw io("Failed to read acknowledged execution work directories", exception);
        }
        int cleaned = 0;
        for (ActiveExecution execution : executions) {
            Path executionRoot = executionWorkDirectory(execution.task());
            if (logArchiver != null && logArchiver.enabled()) {
                // 远端归档未确认时保留本地目录，下一维护周期按稳定幂等键重试。
                try {
                    logArchiver.archive(execution.task(), executionRoot);
                } catch (IOException exception) {
                    continue;
                }
            }
            deleteWorkDirectory(execution.task());
            cleaned += executeUpdate("""
                    UPDATE journal_execution SET work_directory_state = 'DELETED', updated_at = ?
                    WHERE execution_id = ? AND work_directory_state = 'RETAINED'
                    """, Instant.now().toString(), execution.task().executionId().toString());
        }
        return cleaned;
    }

    /**
     * 校验 SQLite WAL、持久化载荷和人工诊断状态。
     *
     * @throws IOException WAL 损坏、载荷损坏或存在人工诊断记录
     */
    public void validate() throws IOException {
        requireInitialized();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA quick_check")) {
            if (!resultSet.next() || !"ok".equalsIgnoreCase(resultSet.getString(1))) {
                throw new IOException("Execution report SQLite WAL integrity check failed");
            }
        } catch (SQLException exception) {
            throw io("Execution report SQLite WAL integrity check failed", exception);
        }
        validatePayloads();
        if (count("SELECT COUNT(*) FROM journal_report WHERE state = 'DIAGNOSTIC'") > 0
                || !jsonFiles(diagnosticRoot).isEmpty()) {
            throw new IOException("Execution journal contains reports requiring manual diagnosis");
        }
    }

    /**
     * 返回不包含任务载荷和租约令牌的 Journal 运维摘要。
     *
     * @return 状态计数、最早重试时间和诊断错误码计数
     * @throws IOException Journal 不可读取
     */
    public JournalStatus status() throws IOException {
        int pending = 0;
        int acknowledged = 0;
        int diagnostic = 0;
        Instant earliestRetryAt = null;
        Map<String, Integer> diagnosticErrorCodes = new LinkedHashMap<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT state, COUNT(*) AS state_count, MIN(next_attempt_at) AS earliest_retry_at
                     FROM journal_report GROUP BY state
                     """)) {
            while (resultSet.next()) {
                int count = resultSet.getInt("state_count");
                switch (resultSet.getString("state")) {
                    case PENDING -> {
                        pending = count;
                        String value = resultSet.getString("earliest_retry_at");
                        earliestRetryAt = value == null ? null : Instant.parse(value);
                    }
                    case ACKNOWLEDGED -> acknowledged = count;
                    case DIAGNOSTIC -> diagnostic = count;
                    default -> throw new IOException("Execution journal contains an unsupported report state");
                }
            }
            try (ResultSet errors = statement.executeQuery("""
                    SELECT error_code, COUNT(*) AS error_count FROM journal_report
                    WHERE state = 'DIAGNOSTIC' GROUP BY error_code ORDER BY error_code
                    """)) {
                while (errors.next()) {
                    diagnosticErrorCodes.put(
                            errors.getString("error_code") == null ? "UNKNOWN" : errors.getString("error_code"),
                            errors.getInt("error_count"));
                }
            }
        } catch (SQLException exception) {
            throw io("Failed to read execution report Journal status", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Failed to parse execution report Journal status", exception);
        }
        return new JournalStatus(pending, acknowledged, diagnostic, earliestRetryAt,
                Map.copyOf(diagnosticErrorCodes));
    }

    /**
     * 返回有界且不包含执行载荷的人工诊断报告摘要。
     *
     * @param limit 最大返回数量
     * @return 诊断报告摘要
     * @throws IOException Journal 不可读取
     */
    public List<DiagnosticReportSummary> diagnosticReports(int limit) throws IOException {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<DiagnosticReportSummary> reports = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, type, status_code, error_code, updated_at FROM journal_report
                WHERE state = ? ORDER BY updated_at, id LIMIT ?
                """)) {
            statement.setString(1, DIAGNOSTIC);
            statement.setInt(2, boundedLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reports.add(new DiagnosticReportSummary(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("type"),
                            resultSet.getInt("status_code"),
                            resultSet.getString("error_code"),
                            Instant.parse(resultSet.getString("updated_at"))));
                }
            }
        } catch (SQLException | RuntimeException exception) {
            throw new IOException("Failed to read diagnostic execution reports", exception);
        }
        return List.copyOf(reports);
    }

    /**
     * 仅在报告仍处于人工诊断状态且错误码与操作员预期一致时重新排队。
     *
     * @param reportId 报告标识
     * @param expectedErrorCode 操作员确认的当前错误码
     * @throws IOException 状态已变化、错误码不匹配或 Journal 不可写
     */
    public void retryDiagnostic(UUID reportId, String expectedErrorCode) throws IOException {
        int updated = executeUpdate("""
                UPDATE journal_report
                SET state = ?, status_code = NULL, error_code = NULL, retry_count = 0,
                    next_attempt_at = NULL, updated_at = ?
                WHERE id = ? AND state = ? AND error_code = ?
                """, PENDING, Instant.now().toString(), reportId.toString(), DIAGNOSTIC, expectedErrorCode);
        if (updated != 1) {
            throw new IOException("Diagnostic report state or expected error code no longer matches");
        }
    }

    /**
     * 将旧进程遗留且尚无任何 Complete 记录的已领取 execution 收敛为失败终态。
     *
     * <p>该方法只能在节点注册和任务领取前调用，避免把当前进程仍在运行的任务误判为中断。</p>
     *
     * @return 本次合成的 FAILED Complete 数量
     * @throws IOException Journal 不可读取或不可写
     */
    public int recoverInterruptedExecutions() throws IOException {
        replayLock.lock();
        try {
            Set<UUID> executionsWithCompletion = completionExecutionIds();
            List<ActiveExecution> interrupted = new ArrayList<>();
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT payload_json FROM journal_execution WHERE state = 'CLAIMED'")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ActiveExecution execution = read(resultSet.getString(1), ActiveExecution.class);
                        if (!executionsWithCompletion.contains(execution.task().executionId())) {
                            interrupted.add(execution);
                        }
                    }
                }
            } catch (SQLException exception) {
                throw io("Failed to read interrupted execution records", exception);
            }
            for (ActiveExecution execution : interrupted) {
                // 使用稳定 Complete ID，恢复过程重复执行或中途崩溃都不会产生第二条终态记录。
                persistCompletion(execution.task(), "FAILED");
            }
            return interrupted.size();
        } finally {
            replayLock.unlock();
        }
    }

    private Set<UUID> completionExecutionIds() throws IOException {
        Set<UUID> executionIds = new java.util.HashSet<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payload_json FROM journal_report WHERE type = 'COMPLETE'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    PendingReport report = read(resultSet.getString(1), PendingReport.class);
                    executionIds.add(report.task().executionId());
                }
            }
        } catch (SQLException exception) {
            throw io("Failed to read completion records during startup recovery", exception);
        }
        return executionIds;
    }

    /**
     * 回放全部未确认上报。
     *
     * @param schedulerClient 调度服务客户端
     * @return 回放数量
     * @throws IOException 读取或上报失败
     */
    public int replayPending(SchedulerClient schedulerClient) throws IOException {
        replayLock.lock();
        try {
            ensureReplayReady();
            List<PendingReportState> reports = pendingReports();
            reports.sort(Comparator.comparingInt(report -> "STEP".equals(report.report().type()) ? 0 : 1));
            int replayed = 0;
            for (PendingReportState state : reports) {
                PendingReport report = state.report();
                if (state.nextAttemptAt() != null && Instant.now().isBefore(state.nextAttemptAt())) {
                    throw new ReportRetryDeferredException(state.nextAttemptAt());
                }
                try {
                    if ("STEP".equals(report.type())) {
                        schedulerClient.reportStep(report.task(), report.step(), report.attempt(), report.status(),
                                report.exitCode(), report.result(), report.errorCode(), report.errorMessage(),
                                report.logIndex() == null ? Map.of() : report.logIndex());
                    } else if ("COMPLETE".equals(report.type())) {
                        schedulerClient.complete(report.task(), new ExecutionCompletion(report.status(),
                                report.compensationStatus() == null ? "NOT_REQUIRED" : report.compensationStatus(),
                                report.compensationRequired(), report.compensationErrorCode()));
                    } else {
                        throw new IOException("Execution report journal contains an unsupported event type");
                    }
                } catch (SchedulerClientException exception) {
                    if (!exception.retryable()) {
                        moveToDiagnostic(report, exception);
                    } else {
                        throw new ReportRetryDeferredException(deferRetry(report, state.retryCount()), exception);
                    }
                    throw exception;
                } catch (IOException exception) {
                    throw new ReportRetryDeferredException(deferRetry(report, state.retryCount()), exception);
                }
                acknowledge(report.id());
                if ("COMPLETE".equals(report.type())) {
                    acknowledgeExecution(report.task(), report.status());
                }
                replayed++;
            }
            return replayed;
        } finally {
            replayLock.unlock();
        }
    }

    Path databasePath() {
        return databasePath;
    }

    /**
     * 释放本进程持有的 Journal 所有权锁。
     *
     * @throws IOException 关闭锁文件失败
     */
    @Override
    @PreDestroy
    public void close() throws IOException {
        closed = true;
        releaseOwnershipLockResources();
    }

    private void releaseOwnershipLockResources() throws IOException {
        IOException failure = null;
        try {
            if (ownershipLock != null && ownershipLock.isValid()) {
                ownershipLock.release();
            }
        } catch (IOException exception) {
            failure = exception;
        } finally {
            ownershipLock = null;
            if (ownershipChannel != null) {
                try {
                    ownershipChannel.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    }
                }
                ownershipChannel = null;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void initialize() throws IOException {
        try {
            Files.createDirectories(journalRoot);
            acquireOwnershipLock();
            try (Connection connection = rawConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS journal_report (
                          id TEXT PRIMARY KEY, type TEXT NOT NULL, payload_json TEXT NOT NULL,
                          state TEXT NOT NULL, status_code INTEGER, error_code TEXT,
                          retry_count INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT,
                          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                        """);
                addColumnIfMissing(connection, "journal_report", "retry_count",
                        "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, "journal_report", "next_attempt_at", "TEXT");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS journal_execution (
                          execution_id TEXT PRIMARY KEY, payload_json TEXT NOT NULL, state TEXT NOT NULL,
                          work_directory_state TEXT NOT NULL, final_status TEXT, updated_at TEXT NOT NULL)
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_journal_report_state ON journal_report(state, type)");
            }
            secureJournalPermissions();
        } catch (SQLException exception) {
            throw io("Failed to initialize execution report SQLite WAL", exception);
        }
    }

    private void secureJournalPermissions() throws IOException {
        try {
            // Journal 包含租约令牌，目录禁止其他用户遍历，数据库仅允许当前用户读写。
            Files.setPosixFilePermissions(journalRoot, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(databasePath, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(ownershipLockPath, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由节点部署权限负责隔离 Journal 根目录。
        }
    }

    private void acquireOwnershipLock() throws IOException {
        ownershipChannel = FileChannel.open(ownershipLockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            ownershipLock = ownershipChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            ownershipLock = null;
        }
        if (ownershipLock == null) {
            throw new IOException("Execution report Journal is owned by another Executor process");
        }
    }

    private void releaseOwnershipLockAfterFailure() {
        try {
            releaseOwnershipLockResources();
        } catch (IOException ignored) {
            // 初始化失败时保留原始异常作为健康降级原因。
        }
    }

    private Connection rawConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition)
            throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                if (column.equals(resultSet.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            // SQLite 不支持通用的 ADD COLUMN IF NOT EXISTS，升级旧 WAL 前先读取表结构。
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private Connection connection() throws SQLException, IOException {
        requireInitialized();
        Connection connection = rawConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        }
        return connection;
    }

    private void requireInitialized() throws IOException {
        if (closed) {
            throw new IOException("Execution report Journal is closed");
        }
        if (initializationFailure == null) {
            return;
        }
        synchronized (initializationMonitor) {
            if (initializationFailure != null) {
                // 临时锁竞争或文件系统故障消失后，允许当前实例原地重新初始化。
                initializationFailure = null;
                try {
                    initialize();
                    importLegacyRecords();
                } catch (IOException exception) {
                    initializationFailure = exception;
                    releaseOwnershipLockAfterFailure();
                }
            }
        }
        if (initializationFailure != null) {
            throw initializationFailure;
        }
    }

    private void persist(PendingReport report) throws IOException {
        String payload = write(report);
        try (Connection connection = connection(); PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO journal_report
                    (id, type, payload_json, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO NOTHING
                    """)) {
            String now = Instant.now().toString();
            bind(insert, report.id().toString(), report.type(), payload, PENDING, now, now);
            if (insert.executeUpdate() == 1) {
                return;
            }
            requireMatchingPayload(connection, report.id(), payload);
        } catch (SQLException exception) {
            throw io("Failed to persist execution report", exception);
        }
    }

    private void requireMatchingPayload(Connection connection, UUID id, String payload)
            throws SQLException, IOException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT payload_json FROM journal_report WHERE id = ?")) {
            query.setString(1, id.toString());
            try (ResultSet resultSet = query.executeQuery()) {
                if (!resultSet.next() || !readTree(payload).equals(readTree(resultSet.getString(1)))) {
                    throw new IOException("Execution report journal identifier conflicts with existing payload");
                }
            }
        }
    }

    private void upsertExecution(ActiveExecution execution) throws IOException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO journal_execution
                (execution_id, payload_json, state, work_directory_state, final_status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(execution_id) DO UPDATE SET
                  state = CASE WHEN journal_execution.state = 'COMPLETION_ACKNOWLEDGED'
                    THEN journal_execution.state ELSE excluded.state END,
                  work_directory_state = CASE WHEN journal_execution.work_directory_state = 'DELETED'
                    THEN journal_execution.work_directory_state ELSE excluded.work_directory_state END,
                  final_status = COALESCE(journal_execution.final_status, excluded.final_status),
                  updated_at = excluded.updated_at,
                  payload_json = CASE WHEN journal_execution.state = 'COMPLETION_ACKNOWLEDGED'
                    AND excluded.state = 'CLAIMED' THEN journal_execution.payload_json ELSE excluded.payload_json END
                """)) {
            bind(statement, execution.task().executionId().toString(), write(execution), execution.state(),
                    execution.workDirectoryState(), execution.finalStatus(), execution.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw io("Failed to persist execution state", exception);
        }
    }

    private List<PendingReportState> pendingReports() throws IOException {
        List<PendingReportState> reports = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                """
                SELECT payload_json, retry_count, next_attempt_at
                FROM journal_report WHERE state = ? ORDER BY created_at, id
                """)) {
            statement.setString(1, PENDING);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String nextAttemptAt = resultSet.getString(3);
                    reports.add(new PendingReportState(
                            read(resultSet.getString(1), PendingReport.class),
                            resultSet.getInt(2),
                            nextAttemptAt == null ? null : Instant.parse(nextAttemptAt)));
                }
            }
        } catch (SQLException exception) {
            throw io("Failed to read pending execution reports", exception);
        }
        return reports;
    }

    /**
     * 检查回放前提。
     *
     * <p>完整 WAL 与历史载荷校验已在启动和健康检查时执行。轮询路径只校验初始化、人工诊断状态及
     * 待回放记录，避免已确认历史记录持续增长后阻塞新任务领取。</p>
     *
     * @throws IOException Journal 不可用或存在需要人工处理的报告
     */
    private void ensureReplayReady() throws IOException {
        requireInitialized();
        if (count("SELECT COUNT(*) FROM journal_report WHERE state = 'DIAGNOSTIC'") > 0
                || !jsonFiles(diagnosticRoot).isEmpty()) {
            throw new IOException("Execution journal contains reports requiring manual diagnosis");
        }
    }

    private Instant deferRetry(PendingReport report, int previousRetryCount) throws IOException {
        int retryCount = Math.min(previousRetryCount + 1, 30);
        long baseDelayMillis = Math.min(30_000L, 1_000L << Math.min(retryCount, 5));
        long jitterMillis = ThreadLocalRandom.current().nextLong(Math.max(1L, baseDelayMillis / 4L));
        Instant retryAt = Instant.now().plusMillis(baseDelayMillis + jitterMillis);
        executeUpdate("""
                UPDATE journal_report SET retry_count = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND state = ?
                """, retryCount, retryAt.toString(),
                Instant.now().toString(), report.id().toString(), PENDING);
        if (meterRegistry != null) {
            // 类型标签只有 STEP/COMPLETE，避免错误消息造成指标高基数。
            meterRegistry.counter("task.report.retry", "type", report.type()).increment();
        }
        return retryAt;
    }

    private void moveToDiagnostic(PendingReport report, SchedulerClientException exception) throws IOException {
        executeUpdate("""
                UPDATE journal_report SET state = ?, status_code = ?, error_code = ?, updated_at = ? WHERE id = ?
                """, DIAGNOSTIC, exception.statusCode(), exception.errorCode(), Instant.now().toString(),
                report.id().toString());
    }

    private record PendingReportState(PendingReport report, int retryCount, Instant nextAttemptAt) {
    }

    /**
     * 不含敏感执行载荷的 Journal 状态摘要。
     *
     * @param pendingReports 待确认上报数量
     * @param acknowledgedReports 已确认上报数量
     * @param diagnosticReports 人工诊断上报数量
     * @param earliestRetryAt 最早下次重试时间
     * @param diagnosticErrorCodes 诊断错误码计数
     */
    public record JournalStatus(int pendingReports, int acknowledgedReports, int diagnosticReports,
                                Instant earliestRetryAt, Map<String, Integer> diagnosticErrorCodes) {
    }

    /**
     * 不包含任务载荷、参数和租约令牌的人工诊断报告摘要。
     *
     * @param reportId 报告标识
     * @param type 报告类型
     * @param statusCode Scheduler HTTP 状态码
     * @param errorCode Scheduler 稳定错误码
     * @param updatedAt 进入当前状态的时间
     */
    public record DiagnosticReportSummary(UUID reportId, String type, int statusCode,
                                          String errorCode, Instant updatedAt) {
    }

    private void validatePayloads() throws IOException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT payload_json FROM journal_report")) {
            while (resultSet.next()) {
                read(resultSet.getString(1), PendingReport.class);
            }
        } catch (SQLException exception) {
            throw io("Failed to validate execution report payloads", exception);
        }
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT payload_json FROM journal_execution")) {
            while (resultSet.next()) {
                read(resultSet.getString(1), ActiveExecution.class);
            }
        } catch (SQLException exception) {
            throw io("Failed to validate execution state payloads", exception);
        }
    }

    private void importLegacyRecords() throws IOException {
        for (Path path : jsonFiles(journalRoot)) {
            persist(objectMapper.readValue(path.toFile(), PendingReport.class));
            archiveLegacy(path, "report-");
        }
        for (Path path : jsonFiles(activeRoot)) {
            upsertExecution(objectMapper.readValue(path.toFile(), ActiveExecution.class));
            archiveLegacy(path, "active-");
        }
        for (Path path : jsonFiles(archiveRoot)) {
            if (path.getFileName().toString().startsWith("execution-")) {
                upsertExecution(objectMapper.readValue(path.toFile(), ActiveExecution.class));
                archiveLegacy(path, "archive-");
            }
        }
    }

    private void archiveLegacy(Path source, String prefix) throws IOException {
        Files.createDirectories(legacyImportedRoot);
        Path target = legacyImportedRoot.resolve(prefix + source.getFileName());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int executeUpdate(String sql, Object... values) throws IOException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw io("Failed to update execution report SQLite WAL", exception);
        }
    }

    private long count(String sql) throws IOException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        } catch (SQLException exception) {
            throw io("Failed to query execution report SQLite WAL", exception);
        }
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private String write(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T read(String value, Class<T> type) throws IOException {
        return objectMapper.readValue(value, type);
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String value) throws IOException {
        return objectMapper.readTree(value);
    }

    private List<Path> jsonFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
        }
    }

    private void deleteWorkDirectory(ClaimedTask task) throws IOException {
        Path taskRoot = workRoot.resolve(task.taskInstanceId().toString()).normalize();
        Path executionRoot = executionWorkDirectory(task);
        if (!executionRoot.startsWith(workRoot) || executionRoot.equals(workRoot)) {
            throw new IOException("Execution work directory escapes configured root");
        }
        if (Files.isDirectory(executionRoot)) {
            try (var paths = Files.walk(executionRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        if (Files.isDirectory(taskRoot)) {
            try (var paths = Files.list(taskRoot)) {
                if (paths.findAny().isEmpty()) {
                    Files.deleteIfExists(taskRoot);
                }
            }
        }
    }

    private Path executionWorkDirectory(ClaimedTask task) throws IOException {
        Path executionRoot = workRoot.resolve(task.taskInstanceId().toString())
                .resolve(task.executionId().toString()).normalize();
        if (!executionRoot.startsWith(workRoot) || executionRoot.equals(workRoot)) {
            throw new IOException("Execution work directory escapes configured root");
        }
        return executionRoot;
    }

    private UUID stableId(String operation, Object... parts) {
        StringBuilder value = new StringBuilder(operation);
        for (Object part : parts) {
            value.append(':').append(part);
        }
        return UUID.nameUUIDFromBytes(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private IOException io(String message, SQLException exception) {
        return new IOException(message, exception);
    }

    private record PendingReport(UUID id, String type, ClaimedTask task, ClaimedStep step, int attempt,
                                 String status, Integer exitCode, Map<String, Object> result,
                                 String errorCode, String errorMessage, Map<String, Object> logIndex,
                                 String compensationStatus, boolean compensationRequired,
                                 String compensationErrorCode) {
    }

    private record ActiveExecution(ClaimedTask task, String state, String workDirectoryState,
                                   String finalStatus, Instant updatedAt) {
    }
}
