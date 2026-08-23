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
        Files.writeString(script, "printf '{\"value\":\"ok\"}' > \"$TASK_RESULT_FILE\"\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("work"), scriptRoot,
                10, 1, 60, 2, Map.of(), Map.of()
        );
        UUID nodeId = UUID.randomUUID();
        ClaimedStep step = new ClaimedStep(
                UUID.randomUUID(), "run_sample", "NORMAL", "sample", "1.0.0", "main.sh",
                List.of(), 10, "FAIL_TASK", 10, 1
        );
        ClaimedTask task = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "sample_task", UUID.randomUUID(),
                Instant.now().plusSeconds(60), Map.of(), List.of(step)
        );
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(nodeId, task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        TaskExecutionWorker worker = new TaskExecutionWorker(
                properties, nodeAgent, schedulerClient, new ScriptProcessRunner(), new ObjectMapper()
        );

        worker.poll();

        waitForCompletion(schedulerClient);
        assertEquals("SUCCEEDED", schedulerClient.stepStatus);
        assertEquals(Map.of("value", "ok"), schedulerClient.stepResult);
        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
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
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            this.completionStatus = status;
        }
    }
}
