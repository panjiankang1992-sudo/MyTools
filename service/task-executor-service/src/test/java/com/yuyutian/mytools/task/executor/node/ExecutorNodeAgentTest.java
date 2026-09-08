package com.yuyutian.mytools.task.executor.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorNetworkIsolationProperties;
import com.yuyutian.mytools.task.executor.runtime.CgroupV2Manager;
import com.yuyutian.mytools.task.executor.runtime.NetworkIsolationManager;
import com.yuyutian.mytools.task.executor.runtime.ExecutionReportJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutorNodeAgentTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldReplayWalBeforeRegisteringNode() throws Exception {
        ExecutorProperties properties = properties();
        try (ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper())) {
            journal.persistCompletion(task(), "SUCCEEDED");
            RecordingSchedulerClient client = new RecordingSchedulerClient();
            ExecutorNodeAgent agent = new ExecutorNodeAgent(client, journal);

            agent.maintainRegistration();

            assertEquals(List.of("complete", "register"), client.events);
            assertNotNull(agent.registration());
        }
    }

    @Test
    void shouldRecoverInterruptedExecutionBeforeRegisteringNode() throws Exception {
        ExecutorProperties properties = properties();
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.py", List.of(), 30, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "startup_recovery",
                UUID.randomUUID(), 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        try (ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper())) {
            journal.recordClaim(task);
            journal.persistStep(task, step, 1, "SUCCEEDED", 0, Map.of(), null, null);
            RecordingSchedulerClient client = new RecordingSchedulerClient() {
                @Override
                public void reportStep(ClaimedTask ignoredTask, ClaimedStep ignoredStep, int attempt, String status,
                                       Integer exitCode, Map<String, Object> result, String errorCode,
                                       String errorMessage) {
                    events.add("step");
                }

                @Override
                public void complete(ClaimedTask ignoredTask, String status) {
                    events.add("complete:" + status);
                }
            };
            ExecutorNodeAgent agent = new ExecutorNodeAgent(client, journal);

            agent.maintainRegistration();

            assertEquals(List.of("step", "complete:FAILED", "register"), client.events);
            assertNotNull(agent.registration());
        }
    }

    @Test
    void shouldRemainUnregisteredWhenWalOwnershipIsUnavailable() throws Exception {
        ExecutorProperties properties = properties();
        ExecutionReportJournal owner = new ExecutionReportJournal(properties, new ObjectMapper());
        try (ExecutionReportJournal blocked = new ExecutionReportJournal(properties, new ObjectMapper())) {
            RecordingSchedulerClient client = new RecordingSchedulerClient();
            ExecutorNodeAgent agent = new ExecutorNodeAgent(client, blocked);

            agent.maintainRegistration();

            assertEquals(List.of(), client.events);
            assertNull(agent.registration());

            owner.close();
            agent.maintainRegistration();

            assertEquals(List.of("register"), client.events);
            assertNotNull(agent.registration());
        } finally {
            owner.close();
        }
    }

    @Test
    void shouldRemainUnregisteredWhilePersistentReportRetryIsDeferred() throws Exception {
        ExecutorProperties properties = properties();
        try (ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper())) {
            journal.persistCompletion(task(), "SUCCEEDED");
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            RecordingSchedulerClient client = new RecordingSchedulerClient() {
                @Override
                public void complete(ClaimedTask task, String status) throws java.io.IOException {
                    attempts.incrementAndGet();
                    throw new java.io.IOException("Scheduler is unavailable");
                }
            };
            ExecutorNodeAgent agent = new ExecutorNodeAgent(client, journal);

            agent.maintainRegistration();
            agent.maintainRegistration();

            assertEquals(1, attempts.get());
            assertEquals(List.of(), client.events);
            assertNull(agent.registration());
        }
    }

    @Test
    void shouldRemainUnregisteredWhenConfiguredCgroupDelegationIsUnavailable() {
        RecordingSchedulerClient client = new RecordingSchedulerClient();
        CgroupV2Manager cgroupV2Manager = new CgroupV2Manager(new ExecutorCgroupProperties(
                true, temporaryDirectory.resolve("not-delegated"), 1024, 2, 100));
        ExecutorNodeAgent agent = new ExecutorNodeAgent(client, null, cgroupV2Manager);

        agent.maintainRegistration();

        assertEquals(List.of(), client.events);
        assertNull(agent.registration());
    }

    @Test
    void shouldRemainUnregisteredWhenConfiguredNetworkIsolationIsUnavailable() {
        RecordingSchedulerClient client = new RecordingSchedulerClient();
        NetworkIsolationManager networkIsolationManager = new NetworkIsolationManager(
                new ExecutorNetworkIsolationProperties(
                        true, temporaryDirectory.resolve("missing-bwrap"), Map.of()));
        ExecutorNodeAgent agent = new ExecutorNodeAgent(client, null, null, networkIsolationManager);

        agent.maintainRegistration();

        assertEquals(List.of(), client.events);
        assertNull(agent.registration());
    }

    private ExecutorProperties properties() {
        return new ExecutorProperties("executor-test", "http://127.0.0.1:23410",
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("scripts"),
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 1, Map.of(), Map.of(), Set.of(), false, Map.of());
    }

    private ClaimedTask task() {
        return new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "startup_recovery",
                UUID.randomUUID(), 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of());
    }

    private static class RecordingSchedulerClient implements SchedulerClient {
        protected final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            events.add("register");
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) {
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
        public void complete(ClaimedTask task, String status) throws java.io.IOException {
            events.add("complete");
        }
    }
}
