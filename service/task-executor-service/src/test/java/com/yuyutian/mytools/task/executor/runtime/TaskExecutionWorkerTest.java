package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                json.dump({"previous": context["stepOutputs"]["run_sample"]["value"]},
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
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(60), Map.of(), List.of(step, checkStep)
        );
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(nodeId, task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(
                properties, nodeAgent, schedulerClient, new ScriptProcessRunner(),
                new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper()
        );

        worker.poll();

        waitForCompletion(schedulerClient);
        assertEquals("SUCCEEDED", schedulerClient.stepStatus);
        assertEquals(Map.of("previous", "ok"), schedulerClient.stepResult);
        assertEquals(3, schedulerClient.reportCount);
        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
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

        waitForCompletion(schedulerClient);
        assertEquals(false, Files.exists(marker));
        assertEquals(2, schedulerClient.reportCount);
        assertEquals(Map.of("handled", true), schedulerClient.stepResult);
        assertEquals("TIMED_OUT", schedulerClient.completionStatus);
    }

    private void waitForCompletion(FakeSchedulerClient schedulerClient) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (schedulerClient.completionStatus == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static final class FakeSchedulerClient implements SchedulerClient {

        private final UUID nodeId;
        private final ClaimedTask task;
        private boolean claimed;
        private volatile String stepStatus;
        private volatile Map<String, Object> stepResult;
        private volatile String completionStatus;
        private volatile int reportCount;

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
            this.reportCount++;
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            this.completionStatus = status;
        }
    }
}
