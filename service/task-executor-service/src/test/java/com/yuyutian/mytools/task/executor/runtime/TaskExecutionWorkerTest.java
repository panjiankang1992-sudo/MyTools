package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutionCompletion;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import com.yuyutian.mytools.task.executor.node.ExecutorNodeAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionWorkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldClaimRunAndCompleteScriptTask() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("scripts");
        Path script = scriptRoot.resolve("sample/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                [ "$CUSTOM_ENV" = "enabled" ] || exit 9
                counter="$(dirname "$TASK_WORK_DIR")/counter"
                if [ ! -f "$counter" ]; then touch "$counter"; exit 7; fi
                printf '{"value":"ok"}' > "$TASK_RESULT_FILE"
                """, StandardCharsets.UTF_8);
        Path checkScript = scriptRoot.resolve("sample/1.0.0/check.py");
        Files.writeString(checkScript, """
                import json, os
                context = json.load(open(os.environ["TASK_CONTEXT_FILE"], encoding="utf-8"))
                json.dump({"previous": context["stepOutputs"]["run_sample"]["value"],
                           "fencingToken": context["fencingToken"]},
                          open(os.environ["TASK_RESULT_FILE"], "w", encoding="utf-8"))
                """, StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("work"), scriptRoot,
                temporaryDirectory.resolve("sdk"),
                Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(),
                false,
                Map.of("sample", Map.of("CUSTOM_ENV", "enabled"))
        );
        UUID nodeId = UUID.randomUUID();
        ClaimedStep step = new ClaimedStep(
                UUID.randomUUID(), "run_sample", "NORMAL", "sample", "1.0.0", "main.sh",
                List.of(), 10, "FAIL_TASK", 10, 2
        );
        ClaimedStep checkStep = new ClaimedStep(
                UUID.randomUUID(), "check_output", "NORMAL", "sample", "1.0.0", "check.py",
                List.of(), 10, "FAIL_TASK", 20, 1
        );
        ClaimedTask task = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "sample_task", UUID.randomUUID(),
                7L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), Map.of(), List.of(step, checkStep)
        );
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(nodeId, task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(
                properties, nodeAgent, schedulerClient, new ScriptProcessRunner(),
                new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper()
        );

        worker.poll();

        waitForCompletion(schedulerClient, worker);
        assertEquals("SUCCEEDED", schedulerClient.stepStatus);
        assertEquals(Map.of("previous", "ok", "fencingToken", 7), schedulerClient.stepResult);
        assertEquals(3, schedulerClient.reportCount);
        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
        Path taskWorkRoot = properties.workRoot().resolve(task.taskInstanceId().toString());
        waitForPathMissing(taskWorkRoot);
        assertFalse(Files.exists(taskWorkRoot));
    }

    @Test
    void shouldRunTimeoutScenarioWhenTaskDeadlineHasExpired() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("deadline-scripts");
        Path packageRoot = scriptRoot.resolve("deadline/1.0.0");
        Files.createDirectories(packageRoot);
        Path marker = temporaryDirectory.resolve("normal-ran");
        Files.writeString(packageRoot.resolve("main.sh"),
                "touch '" + marker + "'\n", StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("timeout.sh"),
                "printf '{\"handled\":true}' > \"$TASK_RESULT_FILE\"\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("deadline-work"), scriptRoot,
                temporaryDirectory.resolve("sdk"),
                Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of()
        );
        ClaimedStep normal = new ClaimedStep(
                UUID.randomUUID(), "run", "NORMAL", "deadline", "1.0.0", "main.sh",
                List.of(), 30, "FAIL_TASK", 10, 1
        );
        ClaimedStep timeout = new ClaimedStep(
                UUID.randomUUID(), "handle_timeout", "ON_TIMEOUT", "deadline", "1.0.0", "timeout.sh",
                List.of(), 30, "IGNORE", 20, 1
        );
        UUID nodeId = UUID.randomUUID();
        ClaimedTask task = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "deadline_task", UUID.randomUUID(),
                Instant.now().plusSeconds(60), Instant.now().minusSeconds(1), Map.of(), List.of(normal, timeout)
        );
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(nodeId, task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(
                properties, nodeAgent, schedulerClient, new ScriptProcessRunner(),
                new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper()
        );

        worker.poll();

        waitForCompletion(schedulerClient, worker);
        assertEquals(false, Files.exists(marker));
        assertEquals(2, schedulerClient.reportCount);
        assertEquals(Map.of("handled", true), schedulerClient.stepResult);
        assertEquals("TIMED_OUT", schedulerClient.completionStatus);
        assertEquals("SUCCEEDED", schedulerClient.executionCompletion.compensationStatus());
        assertFalse(schedulerClient.executionCompletion.compensationRequired());
        assertTrue(Files.exists(properties.workRoot().resolve(task.taskInstanceId().toString())));
    }

    @Test
    void shouldPreservePrimaryFailureAndRequireAttentionWhenCompensationFails() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("compensation-scripts");
        Path packageRoot = scriptRoot.resolve("compensation/1.0.0");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("main.sh"), "exit 9\n", StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("rollback.sh"), "exit 7\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("compensation-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep normal = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "compensation", "1.0.0",
                "main.sh", List.of(), 30, "FAIL_TASK", 10, 1);
        ClaimedStep compensation = new ClaimedStep(UUID.randomUUID(), "rollback", "ON_FAILURE", "compensation",
                "1.0.0", "rollback.sh", List.of(), 30, "FAIL_TASK", 20, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "compensation_task",
                UUID.randomUUID(), 9L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(60),
                Map.of(), List.of(normal, compensation));
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(UUID.randomUUID(), task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper());

        worker.poll();

        waitForCompletion(schedulerClient, worker);
        assertEquals("FAILED", schedulerClient.completionStatus);
        assertEquals("FAILED", schedulerClient.executionCompletion.compensationStatus());
        assertTrue(schedulerClient.executionCompletion.compensationRequired());
        assertEquals("SCRIPT_EXIT_NON_ZERO", schedulerClient.executionCompletion.compensationErrorCode());
        assertEquals(2, schedulerClient.reportCount);
    }

    @Test
    void shouldNotRetryStructuredPermanentBusinessError() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("classified-scripts");
        Path script = scriptRoot.resolve("classified/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                printf '{"code":"REMOTE_AUTH_REJECTED","category":"AUTHENTICATION","retryable":false,"message":"credential rejected"}' > "$TASK_ERROR_FILE"
                exit 1
                """, StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("classified-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "classified", "NORMAL", "classified", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 3);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "classified_task",
                UUID.randomUUID(), 11L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(UUID.randomUUID(), task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper());

        worker.poll();
        waitForCompletion(schedulerClient, worker);

        assertEquals(1, schedulerClient.reportCount);
        assertEquals("REMOTE_AUTH_REJECTED", schedulerClient.lastErrorCode);
        assertEquals("FAILED", schedulerClient.completionStatus);
    }

    @Test
    void shouldRetryStructuredTransientBusinessError() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("transient-scripts");
        Path script = scriptRoot.resolve("classified/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                execution_root="$(dirname "$(dirname "$TASK_WORK_DIR")")"
                if [ ! -f "$execution_root/transient-seen" ]; then
                  touch "$execution_root/transient-seen"
                  printf '{"code":"REMOTE_TEMPORARY_FAILURE","category":"TRANSIENT","retryable":true,"message":"retry later"}' > "$TASK_ERROR_FILE"
                  exit 1
                fi
                printf '{"status":"ok"}' > "$TASK_RESULT_FILE"
                """, StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("transient-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "classified", "NORMAL", "classified", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 3);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "transient_task",
                UUID.randomUUID(), 12L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(UUID.randomUUID(), task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper());

        worker.poll();
        waitForCompletion(schedulerClient, worker);

        assertEquals(2, schedulerClient.reportCount);
        assertEquals("SUCCEEDED", schedulerClient.stepStatus);
        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
    }

    @Test
    void shouldNotCompleteBeforeDeferredStepReportIsReplayed() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("recovery-scripts");
        Path script = scriptRoot.resolve("sample/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "printf '{\"value\":\"ok\"}' > \"$TASK_RESULT_FILE\"\n",
                StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("recovery-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), 9L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        DeferredReportSchedulerClient schedulerClient = new DeferredReportSchedulerClient(task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper());
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper(), journal);

        worker.poll();
        waitForReportAttempts(schedulerClient, 2);
        assertEquals(null, schedulerClient.completionStatus);

        schedulerClient.reportingUnavailable = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (schedulerClient.completionStatus == null && System.nanoTime() < deadline) {
            worker.poll();
            Thread.sleep(20);
        }

        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
        assertEquals("complete", schedulerClient.reportEvents.getLast());
        waitForWorkerIdle(worker);
        assertEquals(0, journal.replayPending(schedulerClient));
        journal.close();
    }

    @Test
    void shouldTerminateProcessTreeAfterLeaseHeartbeatSafetyWindow() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("lease-loss-scripts");
        Path script = scriptRoot.resolve("sample/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "sleep 30 & echo $! > \"$TASK_WORK_DIR/child.pid\"; wait\n",
                StandardCharsets.UTF_8);
        Path workRoot = temporaryDirectory.resolve("lease-loss-work");
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", "", workRoot, scriptRoot,
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                1, 1, 10, 2, 1, 0, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.sh", List.of(), 20, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), 10L, Instant.now().plusSeconds(10), Instant.now().plusSeconds(30),
                Map.of(), List.of(step));
        LeaseFailureSchedulerClient schedulerClient = new LeaseFailureSchedulerClient(task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper());
        Path childPidFile = workRoot.resolve(task.taskInstanceId().toString())
                .resolve(task.executionId().toString()).resolve("run/1/child.pid");

        worker.poll();
        long pidDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.isRegularFile(childPidFile) && System.nanoTime() < pidDeadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.isRegularFile(childPidFile));
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());
        long completionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (schedulerClient.completionStatus == null && System.nanoTime() < completionDeadline) {
            Thread.sleep(20);
        }

        assertEquals("CANCELLED", schedulerClient.completionStatus);
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(schedulerClient.executionHeartbeatAttempts >= 2);
        waitForWorkerIdle(worker);
    }

    @Test
    void shouldReplayJournalThenDrainWithoutClaimingWhenDiskIsLow() throws Exception {
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("disk-work"),
                temporaryDirectory.resolve("scripts"), temporaryDirectory.resolve("sdk"),
                Path.of("/usr/bin/python3"), 10, 1, 60, 1, Map.of(), Map.of(), java.util.Set.of(), false,
                Map.of());
        DiskPressureSchedulerClient schedulerClient = new DiskPressureSchedulerClient();
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutionReportJournal journal = new ExecutionReportJournal(properties, objectMapper);
        DiskSpaceGuard diskGuard = new DiskSpaceGuard(new ExecutorDiskProperties(100, 10),
                () -> new DiskSpaceGuard.DiskUsage(50, 1_000));
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, objectMapper), objectMapper,
                journal, diskGuard);

        worker.poll();
        worker.poll();

        assertEquals(0, schedulerClient.claimCalls);
        assertEquals(1, schedulerClient.statusUpdates);
        assertEquals("DRAINING", schedulerClient.lastStatus);
        journal.close();
    }

    private void waitForCompletion(FakeSchedulerClient schedulerClient, TaskExecutionWorker worker)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((schedulerClient.completionStatus == null || worker.runningTaskCount() != 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, worker.runningTaskCount());
    }

    private void waitForWorkerIdle(TaskExecutionWorker worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.runningTaskCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, worker.runningTaskCount());
    }

    private void waitForPathMissing(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private void waitForReportAttempts(DeferredReportSchedulerClient schedulerClient, int attempts)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (schedulerClient.stepReportAttempts < attempts && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static final class DeferredReportSchedulerClient implements SchedulerClient {

        private final ClaimedTask task;
        private final List<String> reportEvents = new CopyOnWriteArrayList<>();
        private volatile boolean claimed;
        private volatile boolean reportingUnavailable = true;
        private volatile int stepReportAttempts;
        private volatile String completionStatus;

        private DeferredReportSchedulerClient(ClaimedTask task) {
            this.task = task;
        }

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
            if (claimed) {
                return Optional.empty();
            }
            claimed = true;
            return Optional.of(task);
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) {
            return new ExecutionLease(Instant.now().plusSeconds(60), false);
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) throws java.io.IOException {
            stepReportAttempts++;
            reportEvents.add("step");
            if (reportingUnavailable) {
                throw new java.io.IOException("Scheduler unavailable");
            }
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            reportEvents.add("complete");
            completionStatus = status;
        }
    }

    private static final class DiskPressureSchedulerClient implements SchedulerClient {
        private int claimCalls;
        private int statusUpdates;
        private String lastStatus;

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public void updateNodeStatus(UUID nodeId, String status, String reason) {
            statusUpdates++;
            lastStatus = status;
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
            claimCalls++;
            return Optional.empty();
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class LeaseFailureSchedulerClient implements SchedulerClient {

        private final ClaimedTask task;
        private volatile boolean claimed;
        private volatile int executionHeartbeatAttempts;
        private volatile String completionStatus;

        private LeaseFailureSchedulerClient(ClaimedTask task) {
            this.task = task;
        }

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
            if (claimed) {
                return Optional.empty();
            }
            claimed = true;
            return Optional.of(task);
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) throws java.io.IOException {
            executionHeartbeatAttempts++;
            throw new java.io.IOException("Scheduler unavailable");
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) {
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            completionStatus = status;
        }
    }

    private static final class FakeSchedulerClient implements SchedulerClient {

        private final UUID nodeId;
        private final ClaimedTask task;
        private boolean claimed;
        private volatile String stepStatus;
        private volatile Map<String, Object> stepResult;
        private volatile String completionStatus;
        private volatile ExecutionCompletion executionCompletion;
        private volatile int reportCount;
        private volatile String lastErrorCode;

        private FakeSchedulerClient(UUID nodeId, ClaimedTask task) {
            this.nodeId = nodeId;
            this.task = task;
        }

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(nodeId, "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
            if (claimed) {
                return Optional.empty();
            }
            claimed = true;
            return Optional.of(task);
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) {
            return new ExecutionLease(Instant.now().plusSeconds(60), false);
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) {
            this.stepStatus = status;
            this.stepResult = result;
            this.lastErrorCode = errorCode;
            this.reportCount++;
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            this.completionStatus = status;
        }

        @Override
        public void complete(ClaimedTask task, ExecutionCompletion completion) {
            this.executionCompletion = completion;
            this.completionStatus = completion.status();
        }
    }
}
