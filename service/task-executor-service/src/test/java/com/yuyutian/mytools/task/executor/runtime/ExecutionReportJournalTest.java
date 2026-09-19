package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.client.SchedulerClientException;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ExecutionReportJournalTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReplayAndAcknowledgePendingReportsAfterRestart() throws Exception {
        ExecutorProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper();
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of("assetId", "asset-1"), List.of(step));

        ExecutionReportJournal firstProcess = new ExecutionReportJournal(properties, objectMapper);
        firstProcess.recordClaim(task);
        firstProcess.persistStep(task, step, 1, "SUCCEEDED", 0, Map.of("value", "ok"), null, null);
        firstProcess.persistCompletion(task, "SUCCEEDED");
        firstProcess.close();

        RecordingSchedulerClient schedulerClient = new RecordingSchedulerClient();
        ExecutionReportJournal restartedProcess = new ExecutionReportJournal(properties, objectMapper);
        assertEquals(2, restartedProcess.replayPending(schedulerClient));
        assertEquals(1, schedulerClient.stepReports);
        assertEquals(1, schedulerClient.completions);
        assertEquals(0, restartedProcess.replayPending(schedulerClient));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + restartedProcess.databasePath());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                assertTrue(result.next());
                assertEquals("wal", result.getString(1));
            }
            try (var result = statement.executeQuery(
                    "SELECT COUNT(*) FROM journal_report WHERE state = 'ACKNOWLEDGED'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            try (var result = statement.executeQuery(
                    "SELECT COUNT(*) FROM journal_execution WHERE state = 'COMPLETION_ACKNOWLEDGED'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void shouldSynthesizeFailedCompletionForInterruptedClaimExactlyOnce() throws Exception {
        ExecutorProperties properties = properties();
        ClaimedTask task = task();
        ExecutionReportJournal firstProcess = new ExecutionReportJournal(properties, new ObjectMapper());
        firstProcess.recordClaim(task);
        firstProcess.close();

        try (ExecutionReportJournal restartedProcess = new ExecutionReportJournal(properties, new ObjectMapper())) {
            assertEquals(1, restartedProcess.recoverInterruptedExecutions());
            assertEquals(0, restartedProcess.recoverInterruptedExecutions());

            RecordingSchedulerClient schedulerClient = new RecordingSchedulerClient();
            assertEquals(1, restartedProcess.replayPending(schedulerClient));
            assertEquals(1, schedulerClient.completions);
            assertEquals(List.of("FAILED"), schedulerClient.completionStatuses);
            assertEquals(0, restartedProcess.recoverInterruptedExecutions());
        }
    }

    @Test
    void shouldRestrictJournalPermissionsWhenPosixIsAvailable() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        try {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(journal.databasePath()));
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(journal.databasePath().getParent().resolve("executor-owner.lock")));
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(journal.databasePath().getParent()));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统不执行权限位断言。
        }
    }

    @Test
    void shouldRecoverCompletionAfterSchedulerAcceptedButResponseWasLost() throws Exception {
        ExecutorProperties properties = properties();
        ClaimedTask task = task();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ExecutionReportJournal firstProcess = new ExecutionReportJournal(
                properties, new ObjectMapper(), meterRegistry);
        firstProcess.recordClaim(task);
        firstProcess.persistCompletion(task, "SUCCEEDED");
        ResponseLostSchedulerClient schedulerClient = new ResponseLostSchedulerClient();

        assertThrows(IOException.class, () -> firstProcess.replayPending(schedulerClient));
        assertThrows(IOException.class, () -> firstProcess.replayPending(schedulerClient));
        assertEquals(1, schedulerClient.completions);
        assertEquals(1.0, meterRegistry.get("task.report.retry")
                .tag("type", "COMPLETE").counter().count());
        var deferredHealth = new ExecutionJournalHealthIndicator(firstProcess).health();
        assertEquals("OUT_OF_SERVICE", deferredHealth.getStatus().getCode());
        assertEquals(1, deferredHealth.getDetails().get("pendingReports"));
        assertTrue(deferredHealth.getDetails().containsKey("earliestRetryAt"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + firstProcess.databasePath());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT retry_count, next_attempt_at FROM journal_report WHERE state = 'PENDING'
                    """)) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertTrue(Instant.parse(result.getString(2)).isAfter(Instant.now()));
            }
            // 模拟持久退避到期，避免测试依赖真实等待时间。
            statement.executeUpdate("""
                    UPDATE journal_report SET next_attempt_at = '1970-01-01T00:00:00Z'
                    WHERE state = 'PENDING'
                    """);
        }
        firstProcess.close();

        ExecutionReportJournal restartedProcess = new ExecutionReportJournal(properties, new ObjectMapper());
        assertEquals(1, restartedProcess.replayPending(schedulerClient));
        assertEquals(2, schedulerClient.completions);
        assertEquals(0, restartedProcess.replayPending(schedulerClient));
    }

    @Test
    void shouldUpgradeExistingWalWithPersistentRetryColumns() throws Exception {
        ExecutorProperties properties = properties();
        Path database = temporaryDirectory.resolve("work-report-journal/execution-report.db");
        Files.createDirectories(database.getParent());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE journal_report (
                      id TEXT PRIMARY KEY, type TEXT NOT NULL, payload_json TEXT NOT NULL,
                      state TEXT NOT NULL, status_code INTEGER, error_code TEXT,
                      created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE journal_execution (
                      execution_id TEXT PRIMARY KEY, payload_json TEXT NOT NULL, state TEXT NOT NULL,
                      work_directory_state TEXT NOT NULL, final_status TEXT, updated_at TEXT NOT NULL)
                    """);
        }

        try (ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper());
             var connection = DriverManager.getConnection("jdbc:sqlite:" + journal.databasePath());
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA table_info(journal_report)")) {
            java.util.Set<String> columns = new java.util.HashSet<>();
            while (result.next()) {
                columns.add(result.getString("name"));
            }
            assertTrue(columns.contains("retry_count"));
            assertTrue(columns.contains("next_attempt_at"));
        }
    }

    @Test
    void shouldFailValidationAndReplayWhenJournalIsCorrupted() throws Exception {
        ExecutorProperties properties = properties();
        ExecutionReportJournal initialized = new ExecutionReportJournal(properties, new ObjectMapper());
        initialized.close();
        Files.writeString(initialized.databasePath(), "not-a-sqlite-database",
                StandardOpenOption.TRUNCATE_EXISTING);
        ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper());

        assertThrows(IOException.class, journal::validate);
        assertThrows(IOException.class, () -> journal.replayPending(new RecordingSchedulerClient()));
        assertEquals("DOWN", new ExecutionJournalHealthIndicator(journal).health().getStatus().getCode());
    }

    @Test
    void shouldReplayPendingReportsWithoutRevalidatingAcknowledgedHistory() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        RecordingSchedulerClient client = new RecordingSchedulerClient();
        journal.persistCompletion(task(), "SUCCEEDED");
        assertEquals(1, journal.replayPending(client));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + journal.databasePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE journal_report SET payload_json = 'not-json' WHERE state = 'ACKNOWLEDGED'");
        }

        journal.persistCompletion(task(), "SUCCEEDED");
        assertEquals(1, journal.replayPending(client));
        assertEquals(2, client.completions);
        assertThrows(IOException.class, journal::validate);
    }

    @Test
    void shouldImportLegacyPendingJsonIntoSqliteWalOnce() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ClaimedTask task = task();
        ClaimedStep step = task.steps().getFirst();
        Path journalRoot = temporaryDirectory.resolve("work-report-journal");
        Files.createDirectories(journalRoot);
        Path legacyReport = journalRoot.resolve(UUID.randomUUID() + ".json");
        objectMapper.writeValue(legacyReport.toFile(), Map.ofEntries(
                Map.entry("id", UUID.randomUUID()), Map.entry("type", "STEP"), Map.entry("task", task),
                Map.entry("step", step), Map.entry("attempt", 1), Map.entry("status", "SUCCEEDED"),
                Map.entry("exitCode", 0), Map.entry("result", Map.of("value", "ok")),
                Map.entry("errorCode", ""), Map.entry("errorMessage", "")));

        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), objectMapper);
        RecordingSchedulerClient client = new RecordingSchedulerClient();

        assertEquals(1, journal.replayPending(client));
        assertEquals(1, client.stepReports);
        assertFalse(Files.exists(legacyReport));
        try (var paths = Files.list(journalRoot.resolve("legacy-imported"))) {
            assertEquals(1, paths.count());
        }
        journal.close();
        try (ExecutionReportJournal restarted = new ExecutionReportJournal(properties(), objectMapper)) {
            assertEquals(0, restarted.replayPending(client));
        }
    }

    @Test
    void shouldQuarantineNonRetryableReportForManualDiagnosis() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        ClaimedTask task = task();
        journal.recordClaim(task);
        journal.persistCompletion(task, "SUCCEEDED");
        RecordingSchedulerClient client = new RecordingSchedulerClient() {
            @Override
            public void complete(ClaimedTask ignored, String status) throws IOException {
                throw new SchedulerClientException(409, "REPORT_CONFLICT", false);
            }
        };

        ReportRetryDeferredException deferred = assertThrows(
                ReportRetryDeferredException.class, () -> journal.replayPending(client));
        assertEquals(1, journal.status().pendingReports());
        assertEquals(0, journal.status().diagnosticReports());
        long waitMillis = Math.max(1L,
                Duration.between(Instant.now(), deferred.retryAt()).toMillis() + 50L);
        Thread.sleep(waitMillis);
        assertThrows(SchedulerClientException.class, () -> journal.replayPending(client));
        assertThrows(IOException.class, journal::validate);
        var health = new ExecutionJournalHealthIndicator(journal).health();
        assertEquals("DOWN", health.getStatus().getCode());
        assertEquals("Execution journal requires manual diagnosis", health.getDetails().get("error"));
        assertEquals(1, health.getDetails().get("diagnosticReports"));
        assertEquals(Map.of("REPORT_CONFLICT", 1), health.getDetails().get("diagnosticErrorCodes"));
        assertFalse(health.getDetails().toString().contains(task.leaseToken().toString()));

        List<ExecutionReportJournal.DiagnosticReportSummary> diagnostics = journal.diagnosticReports(100);
        assertEquals(1, diagnostics.size());
        ExecutionReportJournal.DiagnosticReportSummary diagnostic = diagnostics.getFirst();
        assertEquals("COMPLETE", diagnostic.type());
        assertEquals(409, diagnostic.statusCode());
        assertEquals("REPORT_CONFLICT", diagnostic.errorCode());
        ExecutionJournalEndpoint endpoint = new ExecutionJournalEndpoint(journal);
        assertFalse(endpoint.status().toString().contains(task.leaseToken().toString()));

        assertThrows(IOException.class,
                () -> journal.retryDiagnostic(diagnostic.reportId(), "EXECUTION_LEASE_LOST"));
        assertEquals(1, journal.status().diagnosticReports());
        endpoint.retry(diagnostic.reportId().toString(), "REPORT_CONFLICT");
        assertEquals("OUT_OF_SERVICE",
                new ExecutionJournalHealthIndicator(journal).health().getStatus().getCode());

        RecordingSchedulerClient recoveredClient = new RecordingSchedulerClient();
        assertEquals(1, journal.replayPending(recoveredClient));
        assertEquals(1, recoveredClient.completions);
        assertEquals("UP", new ExecutionJournalHealthIndicator(journal).health().getStatus().getCode());
    }

    @Test
    void shouldCleanOnlySuccessfulWorkAfterCompletionWasAcknowledged() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        ClaimedTask successfulTask = task();
        Path successfulWork = executionWorkDirectory(successfulTask);
        Files.createDirectories(successfulWork);
        Files.writeString(successfulWork.resolve("result.json"), "{}");
        journal.recordClaim(successfulTask);

        assertEquals(0, journal.cleanupAcknowledgedWorkDirectories());
        assertTrue(Files.exists(successfulWork));

        journal.acknowledgeExecution(successfulTask, "SUCCEEDED");
        assertEquals(1, journal.cleanupAcknowledgedWorkDirectories());
        assertFalse(Files.exists(successfulWork));
        assertEquals(0, journal.cleanupAcknowledgedWorkDirectories());

        ClaimedTask failedTask = task();
        Path failedWork = executionWorkDirectory(failedTask);
        Files.createDirectories(failedWork);
        journal.recordClaim(failedTask);
        journal.acknowledgeExecution(failedTask, "FAILED");

        assertEquals(0, journal.cleanupAcknowledgedWorkDirectories());
        assertTrue(Files.exists(failedWork));
    }

    @Test
    void shouldRetainAcknowledgedWorkUntilLogArchiveSucceeds() throws Exception {
        ExecutionLogArchiver archiver = mock(ExecutionLogArchiver.class);
        when(archiver.enabled()).thenReturn(true);
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper(), null,
                archiver);
        ClaimedTask task = task();
        Path work = executionWorkDirectory(task);
        Files.createDirectories(work);
        Files.writeString(work.resolve("stdout-000001.log"), "output");
        journal.recordClaim(task);
        journal.acknowledgeExecution(task, "SUCCEEDED");
        doThrow(new IOException("Storage Gateway unavailable")).doNothing()
                .when(archiver).archive(task, work);

        assertEquals(0, journal.cleanupAcknowledgedWorkDirectories());
        assertTrue(Files.exists(work));
        journal.close();

        ExecutionLogArchiver restartedArchiver = mock(ExecutionLogArchiver.class);
        when(restartedArchiver.enabled()).thenReturn(true);
        ExecutionReportJournal restarted = new ExecutionReportJournal(properties(), new ObjectMapper(), null,
                restartedArchiver);
        assertEquals(1, restarted.cleanupAcknowledgedWorkDirectories());
        assertFalse(Files.exists(work));
        verify(archiver).archive(task, work);
        verify(restartedArchiver).archive(task, work);
    }

    @Test
    void shouldPersistSameStableReportConcurrentlyWithoutLockFailure() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        ClaimedTask task = task();
        ClaimedStep step = task.steps().getFirst();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (int index = 0; index < 8; index++) {
                int sequence = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (sequence % 2 == 0) {
                        result.put("first", 1);
                        result.put("second", 2);
                    } else {
                        result.put("second", 2);
                        result.put("first", 1);
                    }
                    return journal.persistStep(task, step, 1, "SUCCEEDED", 0, result, null, null);
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            UUID expected = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (var future : futures) {
                assertEquals(expected, future.get(5, TimeUnit.SECONDS));
            }
        }

        RecordingSchedulerClient client = new RecordingSchedulerClient();
        assertEquals(1, journal.replayPending(client));
        assertEquals(1, client.stepReports);
    }

    @Test
    void shouldSerializeConcurrentReplayAndPreserveGlobalReportOrder() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        ClaimedTask task = task();
        ClaimedStep step = task.steps().getFirst();
        journal.persistStep(task, step, 1, "SUCCEEDED", 0, Map.of(), null, null);
        journal.persistCompletion(task, "SUCCEEDED");
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        SchedulerClient client = new RecordingSchedulerClient() {
            @Override
            public void reportStep(ClaimedTask ignoredTask, ClaimedStep ignoredStep, int attempt, String status,
                                   Integer exitCode, Map<String, Object> result, String errorCode,
                                   String errorMessage) {
                events.add("step");
                stepEntered.countDown();
                try {
                    assertTrue(releaseStep.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }

            @Override
            public void complete(ClaimedTask ignored, String status) {
                events.add("complete");
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> journal.replayPending(client));
            assertTrue(stepEntered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> journal.replayPending(client));
            releaseStep.countDown();

            assertEquals(2, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("step", "complete"), events);
    }

    @Test
    void shouldNotRegressAcknowledgedAndDeletedExecutionState() throws Exception {
        ExecutionReportJournal journal = new ExecutionReportJournal(properties(), new ObjectMapper());
        ClaimedTask task = task();
        Files.createDirectories(executionWorkDirectory(task));
        journal.recordClaim(task);
        journal.acknowledgeExecution(task, "SUCCEEDED");
        assertEquals(1, journal.cleanupAcknowledgedWorkDirectories());

        journal.recordClaim(task);

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + journal.databasePath());
             var statement = connection.prepareStatement("""
                     SELECT state, work_directory_state, final_status
                     FROM journal_execution WHERE execution_id = ?
                     """)) {
            statement.setString(1, task.executionId().toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("COMPLETION_ACKNOWLEDGED", result.getString(1));
                assertEquals("DELETED", result.getString(2));
                assertEquals("SUCCEEDED", result.getString(3));
            }
        }
    }

    @Test
    void shouldRejectSecondJournalOwnerInSameProcess() throws Exception {
        ExecutionReportJournal owner = new ExecutionReportJournal(properties(), new ObjectMapper());
        ExecutionReportJournal contender = new ExecutionReportJournal(properties(), new ObjectMapper());

        assertThrows(IOException.class, contender::validate);
        assertEquals("DOWN", new ExecutionJournalHealthIndicator(contender).health().getStatus().getCode());

        owner.close();
        contender.validate();
        contender.close();
    }

    @Test
    void shouldRecoverOwnershipAfterLockHolderIsForciblyTerminated() throws Exception {
        UUID taskInstanceId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), temporaryDirectory.resolve("work").toString(),
                taskInstanceId.toString(), executionId.toString(), leaseToken.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                holder.getInputStream(), StandardCharsets.UTF_8))) {
            boolean persisted = false;
            for (int lineNumber = 0; lineNumber < 16; lineNumber++) {
                String line = output.readLine();
                if (line == null) {
                    break;
                }
                if ("PERSISTED".equals(line)) {
                    persisted = true;
                    break;
                }
            }
            assertTrue(persisted);
            ExecutionReportJournal blocked = new ExecutionReportJournal(properties(), new ObjectMapper());
            assertThrows(IOException.class, blocked::validate);

            holder.destroyForcibly();
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
            blocked.validate();
            RecordingSchedulerClient client = new RecordingSchedulerClient();
            assertEquals(1, blocked.replayPending(client));
            assertEquals(1, client.completions);
            blocked.close();
        } finally {
            if (holder.isAlive()) {
                holder.destroyForcibly();
                holder.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shouldRecoverPersistedStepAfterExecutorProcessIsForciblyTerminated() throws Exception {
        UUID taskInstanceId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        UUID stepDefinitionId = UUID.randomUUID();
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), temporaryDirectory.resolve("work").toString(),
                taskInstanceId.toString(), executionId.toString(), leaseToken.toString(),
                "STEP_ONLY", stepDefinitionId.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                holder.getInputStream(), StandardCharsets.UTF_8))) {
            boolean persisted = false;
            for (int lineNumber = 0; lineNumber < 16; lineNumber++) {
                String line = output.readLine();
                if (line == null) {
                    break;
                }
                if ("PERSISTED".equals(line)) {
                    persisted = true;
                    break;
                }
            }
            assertTrue(persisted);

            holder.destroyForcibly();
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
            try (ExecutionReportJournal recovered = new ExecutionReportJournal(properties(), new ObjectMapper())) {
                assertEquals(1, recovered.recoverInterruptedExecutions());
                RecordingSchedulerClient client = new RecordingSchedulerClient();
                assertEquals(2, recovered.replayPending(client));
                assertEquals(1, client.stepReports);
                assertEquals(List.of("FAILED"), client.completionStatuses);
            }
        } finally {
            if (holder.isAlive()) {
                holder.destroyForcibly();
                holder.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private ClaimedTask task() {
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        return new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of("assetId", "asset-1"), List.of(step));
    }

    private ExecutorProperties properties() {
        return new ExecutorProperties("executor-test", "http://127.0.0.1:23210",
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("scripts"),
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
    }

    private Path executionWorkDirectory(ClaimedTask task) {
        return temporaryDirectory.resolve("work")
                .resolve(task.taskInstanceId().toString())
                .resolve(task.executionId().toString());
    }

    private static class RecordingSchedulerClient implements SchedulerClient {

        private int stepReports;
        protected int completions;
        private final List<String> completionStatuses = new java.util.ArrayList<>();

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) {
            stepReports++;
        }

        @Override
        public void complete(ClaimedTask task, String status) throws IOException {
            completions++;
            completionStatuses.add(status);
        }
    }

    private static final class ResponseLostSchedulerClient extends RecordingSchedulerClient {

        private boolean first = true;

        @Override
        public void complete(ClaimedTask task, String status) throws IOException {
            super.complete(task, status);
            if (first) {
                first = false;
                throw new IOException("Response was lost after Scheduler accepted completion");
            }
        }
    }

    /**
     * 在独立 JVM 中持有所有权锁，供强制崩溃恢复测试使用。
     */
    public static final class LockHolder {

        private LockHolder() {
        }

        /**
         * 获取指定文件锁并持续等待，直到测试进程强制终止。
         *
         * @param arguments 工作根目录、任务实例、执行与租约标识，可选持久化模式和步骤定义标识
         * @throws Exception 获取文件锁失败
         */
        public static void main(String[] arguments) throws Exception {
            Path workRoot = Path.of(arguments[0]);
            ExecutorProperties properties = new ExecutorProperties("crash-holder", "http://127.0.0.1:1",
                    workRoot, workRoot.resolveSibling("scripts"), workRoot.resolveSibling("sdk"),
                    Path.of("/usr/bin/python3"), 10, 1, 60, 1, Map.of(), Map.of(), java.util.Set.of(), false,
                    Map.of());
            boolean stepOnly = arguments.length > 4 && "STEP_ONLY".equals(arguments[4]);
            ClaimedStep step = stepOnly
                    ? new ClaimedStep(UUID.fromString(arguments[5]), "run", "NORMAL", "sample", "1.0.0",
                    "main.py", List.of(), 30, "FAIL_TASK", 10, 1)
                    : null;
            ClaimedTask task = new ClaimedTask(UUID.fromString(arguments[2]), UUID.fromString(arguments[1]), null,
                    "crash_task", UUID.fromString(arguments[3]), 1L, Instant.now().plusSeconds(60),
                    Instant.now().plusSeconds(120), Map.of(), step == null ? List.of() : List.of(step));
            try (ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper())) {
                journal.recordClaim(task);
                if (stepOnly) {
                    // 模拟步骤终态已持久化、尚未成功上报时进程崩溃。
                    journal.persistStep(task, step, 1, "SUCCEEDED", 0, Map.of("value", "ok"), null, null);
                } else {
                    journal.persistCompletion(task, "SUCCEEDED");
                }
                System.out.println("PERSISTED");
                System.out.flush();
                Thread.sleep(Duration.ofMinutes(5));
            }
        }
    }
}
