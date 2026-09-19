package com.yuyutian.mytools.task.executor.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.ClaimCapacityReservedException;
import com.yuyutian.mytools.task.executor.client.ClaimedStep;
import com.yuyutian.mytools.task.executor.client.ClaimedTask;
import com.yuyutian.mytools.task.executor.client.ExecutionLease;
import com.yuyutian.mytools.task.executor.client.ExecutionCompletion;
import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.client.SchedulerClientException;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import com.yuyutian.mytools.task.executor.node.ExecutorNodeAgent;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationRun;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import com.yuyutian.mytools.task.executor.runtime.adaptation.NovelAdaptationTaskHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskExecutionWorkerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldFollowDeepestChildChainWithoutClaimingBlockingSiblings() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("concurrent-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("concurrent-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 4, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask rootTask = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_orchestrator", UUID.randomUUID(),
                1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        ClaimedTask queuedRootSibling = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "queued_root_sibling", UUID.randomUUID(),
                2L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        ClaimedTask childOrchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(),
                "child_orchestrator", UUID.randomUUID(), 3L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), true, Map.of(), List.of(step));
        ClaimedTask queuedChildSibling = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(),
                "queued_child_sibling", UUID.randomUUID(), 4L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), true, Map.of(), List.of(step));
        ClaimedTask grandchildOrchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), childOrchestrator.taskInstanceId(),
                "grandchild_orchestrator", UUID.randomUUID(), 5L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), true, Map.of(), List.of(step));
        ClaimedTask leafTask = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), grandchildOrchestrator.taskInstanceId(),
                "leaf_task", UUID.randomUUID(), 6L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(
                List.of(rootTask, queuedRootSibling), 4, Map.of(
                        rootTask.taskInstanceId(), 4,
                        queuedRootSibling.taskInstanceId(), 4,
                        childOrchestrator.taskInstanceId(), 3,
                        queuedChildSibling.taskInstanceId(), 3,
                        grandchildOrchestrator.taskInstanceId(), 2,
                        leafTask.taskInstanceId(), 1));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(4);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();

        assertEquals(1, worker.runningTaskCount());
        assertEquals(1, schedulerClient.rootClaimCalls.get());
        assertEquals(1, schedulerClient.tasks.size());

        // 同层兄弟已排队时，只领取当前最深任务的直接子任务，为后续层级继续保留槽位。
        schedulerClient.tasks.add(childOrchestrator);
        schedulerClient.tasks.add(queuedChildSibling);
        worker.poll();
        assertEquals(2, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.contains(queuedRootSibling));
        assertTrue(schedulerClient.tasks.contains(queuedChildSibling));

        schedulerClient.tasks.add(grandchildOrchestrator);
        worker.poll();
        assertEquals(3, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.contains(queuedChildSibling));

        schedulerClient.tasks.add(leafTask);
        worker.poll();
        assertTrue(processRunner.awaitStarted());
        assertEquals(4, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.contains(queuedRootSibling));
        assertTrue(schedulerClient.tasks.contains(queuedChildSibling));
        assertEquals(List.of(
                Set.of(rootTask.taskInstanceId()),
                Set.of(childOrchestrator.taskInstanceId()),
                Set.of(grandchildOrchestrator.taskInstanceId())), schedulerClient.successfulParentScopes);
        processRunner.release();
        waitForWorkerIdle(worker);
        assertEquals(4, schedulerClient.completions.get());
    }

    @Test
    void shouldFillAvailableCapacityWithSiblingLeafTasks() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("leaf-sibling-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("leaf-sibling-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 8, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask rootTask = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_orchestrator", UUID.randomUUID(),
                1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(List.of(rootTask));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(7);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();
        for (int index = 0; index < 6; index++) {
            schedulerClient.tasks.add(new ClaimedTask(
                    UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "leaf_" + index,
                    UUID.randomUUID(), index + 2L, Instant.now().plusSeconds(60),
                    Instant.now().plusSeconds(120), false, Map.of(), List.of(step)));
        }
        worker.poll();

        assertTrue(processRunner.awaitStarted());
        assertEquals(7, worker.runningTaskCount());
        assertEquals(6, schedulerClient.successfulParentScopes.size());
        assertTrue(schedulerClient.successfulParentScopes.stream()
                .allMatch(scope -> scope.equals(Set.of(rootTask.taskInstanceId()))));
        processRunner.release();
        waitForWorkerIdle(worker);
        assertEquals(7, schedulerClient.completions.get());
    }

    @Test
    void shouldFallBackToShallowerOrchestratorWhenDeepestChildrenAreAlreadyRunning() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("depth-fallback-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("depth-fallback-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 6, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask rootTask = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_orchestrator", UUID.randomUUID(),
                1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        ClaimedTask childOrchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "child_orchestrator",
                UUID.randomUUID(), 2L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        ClaimedTask deepestLeaf = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), childOrchestrator.taskInstanceId(), "deepest_leaf",
                UUID.randomUUID(), 3L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        ClaimedTask shallowLeaf = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "shallow_leaf",
                UUID.randomUUID(), 4L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(List.of(rootTask));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(4);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();
        schedulerClient.tasks.add(childOrchestrator);
        worker.poll();
        schedulerClient.tasks.add(deepestLeaf);
        schedulerClient.tasks.add(shallowLeaf);
        worker.poll();

        assertTrue(processRunner.awaitStarted());
        assertEquals(4, worker.runningTaskCount());
        assertEquals(List.of(
                Set.of(rootTask.taskInstanceId()),
                Set.of(childOrchestrator.taskInstanceId()),
                Set.of(rootTask.taskInstanceId())), schedulerClient.successfulParentScopes);
        processRunner.release();
        waitForWorkerIdle(worker);
        assertEquals(4, schedulerClient.completions.get());
    }

    @Test
    void shouldKeepLastSlotFreeWhenDeepestChildIsCapacityBlocked() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("capacity-reservation-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210",
                temporaryDirectory.resolve("capacity-reservation-work"), scriptRoot,
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 6, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask rootTask = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_orchestrator", UUID.randomUUID(),
                1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        ClaimedTask childOrchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "child_orchestrator",
                UUID.randomUUID(), 2L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        List<ClaimedTask> shallowLeaves = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> new ClaimedTask(
                        UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "shallow_leaf_" + index,
                        UUID.randomUUID(), index + 3L, Instant.now().plusSeconds(60),
                        Instant.now().plusSeconds(120), false, Map.of(), List.of(step)))
                .toList();
        ClaimedTask deepestOrchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), childOrchestrator.taskInstanceId(),
                "deepest_orchestrator", UUID.randomUUID(), 6L, Instant.now().plusSeconds(60),
                Instant.now().plusSeconds(120), true, Map.of(), List.of(step));
        ClaimedTask shallowBacklog = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), rootTask.taskInstanceId(), "shallow_backlog",
                UUID.randomUUID(), 7L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                false, Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(
                List.of(rootTask), 6, Map.of(
                        rootTask.taskInstanceId(), 4,
                        childOrchestrator.taskInstanceId(), 3,
                        deepestOrchestrator.taskInstanceId(), 2));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(5);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();
        schedulerClient.tasks.add(childOrchestrator);
        schedulerClient.tasks.addAll(shallowLeaves);
        worker.poll();
        assertTrue(processRunner.awaitStarted());
        assertEquals(5, worker.runningTaskCount());

        schedulerClient.tasks.add(deepestOrchestrator);
        schedulerClient.tasks.add(shallowBacklog);
        worker.poll();

        assertEquals(5, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.contains(deepestOrchestrator));
        assertTrue(schedulerClient.tasks.contains(shallowBacklog));
        processRunner.release();
        waitForWorkerIdle(worker);
    }

    @Test
    void shouldTakeOverQueuedChildBeforeContinuousRootBacklogOnEmptyNode() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("takeover-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("takeover-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 4, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        UUID remoteParentId = UUID.randomUUID();
        ClaimedTask orphanChild = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), remoteParentId, "orphan_child", UUID.randomUUID(),
                1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), Map.of(), List.of(step));
        ClaimedTask rootOne = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_one", UUID.randomUUID(),
                2L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), Map.of(), List.of(step));
        ClaimedTask rootTwo = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "root_two", UUID.randomUUID(),
                3L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(
                List.of(rootOne, rootTwo, orphanChild));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(3);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();

        assertTrue(processRunner.awaitStarted());
        assertEquals(orphanChild.taskInstanceId(), schedulerClient.claimedTaskIds.getFirst());
        assertTrue(schedulerClient.claimedTaskIds.contains(rootOne.taskInstanceId()));
        assertTrue(schedulerClient.claimedTaskIds.contains(rootTwo.taskInstanceId()));
        processRunner.release();
        waitForWorkerIdle(worker);
    }

    @Test
    void shouldKeepRemoteChildReservationScopeAcrossPolls() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("remote-reservation-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210",
                temporaryDirectory.resolve("remote-reservation-work"), scriptRoot,
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 4, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        List<ClaimedTask> initialRoots = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> new ClaimedTask(
                        UUID.randomUUID(), UUID.randomUUID(), null, "initial_root_" + index,
                        UUID.randomUUID(), index + 1L, Instant.now().plusSeconds(60),
                        Instant.now().plusSeconds(120), false, Map.of(), List.of(step)))
                .toList();
        ClaimedTask remoteChild = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "remote_child_orchestrator",
                UUID.randomUUID(), 4L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                true, Map.of(), List.of(step));
        ClaimedTask unrelatedRoot = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "unrelated_root", UUID.randomUUID(),
                5L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                false, Map.of(), List.of(step));
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(
                initialRoots, 4, Map.of(remoteChild.taskInstanceId(), 2));
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        Set<UUID> initialRootIds = initialRoots.stream().map(ClaimedTask::taskInstanceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        SelectiveBlockingProcessRunner processRunner = new SelectiveBlockingProcessRunner(initialRootIds);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();
        assertTrue(processRunner.awaitStarted(3));
        schedulerClient.tasks.add(remoteChild);
        schedulerClient.tasks.add(unrelatedRoot);

        worker.poll();
        assertEquals(3, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.contains(remoteChild));
        processRunner.releaseOneLeaf();
        waitForRunningTaskCount(worker, 2);
        worker.poll();

        assertTrue(processRunner.awaitStarted(4));
        assertTrue(schedulerClient.claimedTaskIds.contains(remoteChild.taskInstanceId()));
        assertTrue(schedulerClient.tasks.contains(unrelatedRoot));
        processRunner.releaseAll();
        waitForWorkerIdle(worker);
    }

    @Test
    void shouldUseAllTwelveSlotsForIndependentRootLeafTasks() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("root-leaf-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("root-leaf-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 12, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        List<ClaimedTask> leafTasks = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new ClaimedTask(
                        UUID.randomUUID(), UUID.randomUUID(), null, "root_leaf_" + index, UUID.randomUUID(),
                        index + 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                        Map.of(), List.of(step)))
                .toList();
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(leafTasks);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        BlockingProcessRunner processRunner = new BlockingProcessRunner(12);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();

        assertTrue(processRunner.awaitStarted());
        assertEquals(12, worker.runningTaskCount());
        assertTrue(schedulerClient.tasks.isEmpty());
        processRunner.release();
        waitForWorkerIdle(worker);
        assertEquals(12, schedulerClient.completions.get());
    }

    @Test
    void shouldAdvanceLastOrchestratorAfterOneOfElevenLeavesFinishes() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("late-orchestrator-scripts");
        Path script = scriptRoot.resolve("concurrent/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "exit 0\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("late-orchestrator-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 12, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "concurrent", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        List<ClaimedTask> leafTasks = java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> new ClaimedTask(
                        UUID.randomUUID(), UUID.randomUUID(), null, "root_leaf_" + index, UUID.randomUUID(),
                        index + 1L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                        Map.of(), List.of(step)))
                .toList();
        ClaimedTask orchestrator = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "late_orchestrator", UUID.randomUUID(),
                20L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120), true,
                Map.of(), List.of(step));
        List<ClaimedTask> initialTasks = new java.util.ArrayList<>(leafTasks);
        initialTasks.add(orchestrator);
        DepthFirstSchedulerClient schedulerClient = new DepthFirstSchedulerClient(initialTasks);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        Set<UUID> leafTaskIds = leafTasks.stream().map(ClaimedTask::taskInstanceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        SelectiveBlockingProcessRunner processRunner = new SelectiveBlockingProcessRunner(leafTaskIds);
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();
        assertTrue(processRunner.awaitStarted(12));
        ClaimedTask child = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), orchestrator.taskInstanceId(), "orchestrator_child",
                UUID.randomUUID(), 21L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        schedulerClient.tasks.add(child);
        processRunner.releaseOneLeaf();
        waitForRunningTaskCount(worker, 11);
        worker.poll();

        assertTrue(processRunner.awaitStarted(13));
        assertEquals(12, worker.runningTaskCount());
        assertTrue(schedulerClient.successfulParentScopes.contains(Set.of(orchestrator.taskInstanceId())));
        processRunner.releaseAll();
        waitForWorkerIdle(worker);
        assertEquals(13, schedulerClient.completions.get());
    }

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
    void shouldContinueAfterIgnoredNormalStepTimesOut() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("ignored-timeout-scripts");
        Path packageRoot = scriptRoot.resolve("download/1.0.0");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("generate.py"), "", StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("record.py"), "", StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("ignored-timeout-work"),
                scriptRoot, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep generateTags = new ClaimedStep(
                UUID.randomUUID(), "generate_tags", "NORMAL", "download", "1.0.0", "generate.py",
                List.of(), 30, "IGNORE", 40, 1);
        ClaimedStep recordTags = new ClaimedStep(
                UUID.randomUUID(), "record_tags", "NORMAL", "download", "1.0.0", "record.py",
                List.of(), 30, "FAIL_TASK", 50, 1);
        ClaimedTask task = new ClaimedTask(
                UUID.randomUUID(), UUID.randomUUID(), null, "download_task", UUID.randomUUID(),
                13L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(generateTags, recordTags));
        FakeSchedulerClient schedulerClient = new FakeSchedulerClient(UUID.randomUUID(), task);
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        IgnoredTimeoutProcessRunner processRunner = new IgnoredTimeoutProcessRunner();
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                processRunner, new ScriptReleaseVerifier(properties, new ObjectMapper()), new ObjectMapper());

        worker.poll();

        waitForCompletion(schedulerClient, worker);
        assertEquals(List.of("generate_tags", "record_tags"), processRunner.executedSteps);
        assertEquals(2, schedulerClient.reportCount);
        assertEquals("SUCCEEDED", schedulerClient.stepStatus);
        assertEquals(Map.of("tagStatus", "FAILED"), schedulerClient.stepResult);
        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
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
    void shouldBoundedlyReplayOneStepReportConflictWithoutFailingTask() throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("conflict-recovery-scripts");
        Path script = scriptRoot.resolve("sample/1.0.0/main.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "printf '{\"value\":\"ok\"}' > \"$TASK_RESULT_FILE\"\n",
                StandardCharsets.UTF_8);
        ExecutorProperties properties = new ExecutorProperties(
                "executor-test", "http://127.0.0.1:23210",
                temporaryDirectory.resolve("conflict-recovery-work"), scriptRoot,
                temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 2, Map.of(), Map.of(), java.util.Set.of(), false, Map.of());
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "sample", "1.0.0",
                "main.sh", List.of(), 10, "FAIL_TASK", 10, 1);
        ClaimedTask task = new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, "sample_task",
                UUID.randomUUID(), 9L, Instant.now().plusSeconds(60), Instant.now().plusSeconds(120),
                Map.of(), List.of(step));
        DeferredReportSchedulerClient schedulerClient = new DeferredReportSchedulerClient(task);
        schedulerClient.reportingUnavailable = false;
        schedulerClient.conflictOnce = true;
        ExecutorNodeAgent nodeAgent = new ExecutorNodeAgent(schedulerClient);
        nodeAgent.maintainRegistration();
        ExecutionReportJournal journal = new ExecutionReportJournal(properties, new ObjectMapper());
        TaskExecutionWorker worker = new TaskExecutionWorker(properties, nodeAgent, schedulerClient,
                new ScriptProcessRunner(), new ScriptReleaseVerifier(properties, new ObjectMapper()),
                new ObjectMapper(), journal);

        worker.poll();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (schedulerClient.completionStatus == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertEquals("SUCCEEDED", schedulerClient.completionStatus);
        assertTrue(schedulerClient.stepReportAttempts >= 2);
        assertEquals(0, journal.status().pendingReports());
        assertEquals(0, journal.status().diagnosticReports());
        waitForWorkerIdle(worker);
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
        assertEquals(nodeAgent.instanceId(), schedulerClient.lastExpectedInstanceId);
        assertNotEquals(nodeAgent.registration().id(), schedulerClient.lastExpectedInstanceId);
        journal.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"})
    void shouldRouteAdaptationToHostWithoutGenericContextCredentialsOrResults(String status) throws Exception {
        Path scriptRoot = temporaryDirectory.resolve("protected-scripts");
        Path script = scriptRoot.resolve("reader_adapt_novel_chapter/1.0.0/scripts/main.py");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "raise SystemExit(1)\n", StandardCharsets.UTF_8);
        ExecutorProperties properties = adaptationProperties(scriptRoot);
        ClaimedStep step = new ClaimedStep(UUID.randomUUID(), "run", "NORMAL", "reader_adapt_novel_chapter", "1.0.0",
                "a".repeat(64), "scripts/main.py", List.of(), 900, "FAIL_TASK", 1, 1);
        ClaimedTask task = adaptationTask("reader_adapt_novel_chapter", List.of(step));
        NovelAdaptationTaskHost host = mock(NovelAdaptationTaskHost.class);
        ScriptProcessRunner generic = mock(ScriptProcessRunner.class);
        ScriptReleaseVerifier verifier = mock(ScriptReleaseVerifier.class);
        when(host.execute(eq(task), eq(step), eq(script), any(), any())).thenAnswer(invocation -> {
            Path work = invocation.getArgument(3);
            // 在清理成功现场之前断言，避免成功后的目录删除掩盖意外文件写入。
            assertFalse(Files.exists(work));
            ErrorCode error = "SUCCEEDED".equals(status) ? null : ErrorCode.FENCED;
            return new NovelAdaptationTaskHost.Execution(new NovelAdaptationRun.Result(status, error),
                    new ScriptExecutionResult("SUCCEEDED".equals(status) ? 0 : 1, "", "", java.time.Duration.ZERO,
                            "TIMED_OUT".equals(status), "CANCELLED".equals(status)));
        });
        FakeSchedulerClient scheduler = executeAdaptationFixture(properties, task, generic, verifier, host);
        assertEquals(status, scheduler.completionStatus);
        assertEquals(status, scheduler.stepStatus);
        assertEquals(Map.of(), scheduler.stepResult);
        assertEquals(1, scheduler.reportCount);
        verify(host).validate(task);
        verify(host).execute(eq(task), eq(step), eq(script), any(), any());
        verify(verifier).verifyEntrypoint(step.scriptPackage(), step.scriptVersion(), step.entrypoint(), script, step.scriptReleaseDigest());
        verifyNoInteractions(generic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader_adapt_novel_chapter", "READER_ADAPT_NOVEL_CHAPTER"})
    void shouldRejectProtectedEmptyTaskWithoutHost(String name) throws Exception {
        ExecutorProperties properties = adaptationProperties(temporaryDirectory.resolve("scripts"));
        ScriptProcessRunner generic = mock(ScriptProcessRunner.class);
        var scheduler = executeAdaptationFixture(properties, adaptationTask(name, List.of()), generic,
                mock(ScriptReleaseVerifier.class), null);
        assertEquals("FAILED", scheduler.completionStatus);
        assertEquals(0, scheduler.reportCount);
        verifyNoInteractions(generic);
    }

    @Test
    void shouldRejectGenericEmptyTaskOnDedicatedRecoveryHost() throws Exception {
        NovelAdaptationTaskHost host = mock(NovelAdaptationTaskHost.class);
        when(host.dedicated()).thenReturn(true);
        ScriptProcessRunner generic = mock(ScriptProcessRunner.class);
        var scheduler = executeAdaptationFixture(adaptationProperties(temporaryDirectory.resolve("scripts")),
                adaptationTask("generic_task", List.of()), generic, mock(ScriptReleaseVerifier.class), host);
        assertEquals("FAILED", scheduler.completionStatus);
        verify(host).dedicated();
        verifyNoMoreInteractions(host);
        verifyNoInteractions(generic);
    }

    @Test
    void shouldRejectInvalidProtectedContractBeforeResolvingScriptOrWritingFiles() throws Exception {
        NovelAdaptationTaskHost host = mock(NovelAdaptationTaskHost.class);
        ClaimedTask task = adaptationTask("reader_adapt_novel_chapter", List.of());
        doThrow(new ReaderAdaptationException(ErrorCode.DISABLED, 0)).when(host).validate(task);
        ScriptProcessRunner generic = mock(ScriptProcessRunner.class);
        ScriptReleaseVerifier verifier = mock(ScriptReleaseVerifier.class);
        var scheduler = executeAdaptationFixture(adaptationProperties(temporaryDirectory.resolve("scripts")), task, generic, verifier, host);
        assertEquals("FAILED", scheduler.completionStatus);
        verify(host).validate(task);
        verifyNoMoreInteractions(host);
        verifyNoInteractions(generic, verifier);
    }

    private ExecutorProperties adaptationProperties(Path scripts) {
        return new ExecutorProperties("executor-test", "http://127.0.0.1:23210", temporaryDirectory.resolve("protected-work"),
                scripts, temporaryDirectory.resolve("sdk"), Path.of("/usr/bin/python3"),
                10, 1, 60, 1, Map.of(), Map.of(), Set.of(), false,
                Map.of("reader_adapt_novel_chapter", Map.of("UNSAFE_TEST_ONLY", "must-not-forward")));
    }

    private ClaimedTask adaptationTask(String name, List<ClaimedStep> steps) {
        return new ClaimedTask(UUID.randomUUID(), UUID.randomUUID(), null, name, UUID.randomUUID(), 1L,
                Instant.now().plusSeconds(60), Instant.now().plusSeconds(900),
                Map.of("adaptationId", UUID.randomUUID().toString()), steps);
    }

    private FakeSchedulerClient executeAdaptationFixture(ExecutorProperties properties, ClaimedTask task,
            ScriptProcessRunner processes, ScriptReleaseVerifier verifier, NovelAdaptationTaskHost host) throws Exception {
        FakeSchedulerClient scheduler = new FakeSchedulerClient(UUID.randomUUID(), task);
        ExecutorNodeAgent node = new ExecutorNodeAgent(scheduler); node.maintainRegistration();
        ObjectMapper json = new ObjectMapper();
        try (var journal = new ExecutionReportJournal(properties, json)) {
            TaskExecutionWorker worker = new TaskExecutionWorker(properties, node, scheduler, processes, verifier, json,
                    journal, new DiskSpaceGuard(properties, new ExecutorDiskProperties(0, 0)), host);
            worker.poll(); waitForCompletion(scheduler, worker);
        }
        return scheduler;
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

    private void waitForRunningTaskCount(TaskExecutionWorker worker, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.runningTaskCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, worker.runningTaskCount());
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
        private volatile boolean conflictOnce;
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
            if (conflictOnce) {
                conflictOnce = false;
                throw new SchedulerClientException(409, "REPORT_CONFLICT", false);
            }
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

    private static final class BlockingProcessRunner extends ScriptProcessRunner {

        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingProcessRunner(int expectedTasks) {
            this.started = new CountDownLatch(expectedTasks);
        }

        @Override
        public ScriptExecutionResult run(ScriptExecutionRequest request) throws java.io.IOException {
            started.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new java.io.IOException("Concurrent task release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Concurrent task execution was interrupted", exception);
            }
            return new ScriptExecutionResult(0, "", "", java.time.Duration.ZERO, false, false);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(3, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class SelectiveBlockingProcessRunner extends ScriptProcessRunner {

        private final Set<UUID> leafTaskIds;
        private final AtomicInteger started = new AtomicInteger();
        private final Semaphore leafPermits = new Semaphore(0);
        private final CountDownLatch orchestratorRelease = new CountDownLatch(1);

        private SelectiveBlockingProcessRunner(Set<UUID> leafTaskIds) {
            this.leafTaskIds = leafTaskIds;
        }

        @Override
        public ScriptExecutionResult run(ScriptExecutionRequest request) throws java.io.IOException {
            UUID taskId = UUID.fromString(request.workingDirectory().getParent().getParent().getParent()
                    .getFileName().toString());
            started.incrementAndGet();
            try {
                boolean released = leafTaskIds.contains(taskId)
                        ? leafPermits.tryAcquire(5, TimeUnit.SECONDS)
                        : orchestratorRelease.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new java.io.IOException("Selective concurrent task release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Selective task execution was interrupted", exception);
            }
            return new ScriptExecutionResult(0, "", "", java.time.Duration.ZERO, false, false);
        }

        private boolean awaitStarted(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (started.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            return started.get() >= expected;
        }

        private void releaseOneLeaf() {
            leafPermits.release();
        }

        private void releaseAll() {
            leafPermits.release(leafTaskIds.size());
            orchestratorRelease.countDown();
        }
    }

    private static final class IgnoredTimeoutProcessRunner extends ScriptProcessRunner {

        private final List<String> executedSteps = new CopyOnWriteArrayList<>();

        @Override
        public ScriptExecutionResult run(ScriptExecutionRequest request) throws java.io.IOException {
            String stepName = request.workingDirectory().getParent().getFileName().toString();
            executedSteps.add(stepName);
            if ("generate_tags".equals(stepName)) {
                return new ScriptExecutionResult(-1, "", "tag generation timed out",
                        java.time.Duration.ofSeconds(30), true, false);
            }
            Files.writeString(Path.of(request.environment().get("TASK_RESULT_FILE")),
                    "{\"tagStatus\":\"FAILED\"}", StandardCharsets.UTF_8);
            return new ScriptExecutionResult(0, "", "", java.time.Duration.ZERO, false, false);
        }
    }

    private static final class DepthFirstSchedulerClient implements SchedulerClient {

        private final List<ClaimedTask> tasks = new CopyOnWriteArrayList<>();
        private final List<Set<UUID>> successfulParentScopes = new CopyOnWriteArrayList<>();
        private final List<UUID> claimedTaskIds = new CopyOnWriteArrayList<>();
        private final AtomicInteger rootClaimCalls = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger runningClaims = new AtomicInteger();
        private final int maximumTasks;
        private final Map<UUID, Integer> minimumSlotsByTaskId;

        private DepthFirstSchedulerClient(List<ClaimedTask> tasks) {
            this(tasks, Integer.MAX_VALUE, Map.of());
        }

        private DepthFirstSchedulerClient(List<ClaimedTask> tasks, int maximumTasks,
                                          Map<UUID, Integer> minimumSlotsByTaskId) {
            this.tasks.addAll(tasks);
            this.maximumTasks = maximumTasks;
            this.minimumSlotsByTaskId = Map.copyOf(minimumSlotsByTaskId);
        }

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId) throws IOException {
            return removeFirst(task -> true);
        }

        @Override
        public Optional<ClaimedTask> claim(UUID nodeId, UUID instanceId, boolean childTaskOnly) throws IOException {
            return childTaskOnly
                    ? removeFirst(task -> task.parentTaskInstanceId() != null)
                    : claim(nodeId, instanceId);
        }

        @Override
        public Optional<ClaimedTask> claimRootTask(UUID nodeId, UUID instanceId) throws IOException {
            rootClaimCalls.incrementAndGet();
            return removeFirst(task -> task.parentTaskInstanceId() == null);
        }

        @Override
        public Optional<ClaimedTask> claimDirectChildTask(UUID nodeId, UUID instanceId,
                                                          Set<UUID> parentTaskInstanceIds) throws IOException {
            Optional<ClaimedTask> claimed = removeFirst(
                    task -> task.parentTaskInstanceId() != null
                            && parentTaskInstanceIds.contains(task.parentTaskInstanceId()));
            if (claimed.isPresent()) {
                successfulParentScopes.add(Set.copyOf(parentTaskInstanceIds));
            }
            return claimed;
        }

        private Optional<ClaimedTask> removeFirst(java.util.function.Predicate<ClaimedTask> predicate)
                throws IOException {
            List<ClaimedTask> scopedCandidates = tasks.stream().filter(predicate).toList();
            Optional<ClaimedTask> highestPriority = scopedCandidates.stream().findFirst();
            if (highestPriority.isPresent()) {
                int minimumSlots = minimumSlotsByTaskId.getOrDefault(
                        highestPriority.get().taskInstanceId(), 1);
                if (minimumSlots > 1 && maximumTasks >= minimumSlots
                        && maximumTasks - runningClaims.get() < minimumSlots) {
                    throw new ClaimCapacityReservedException();
                }
            }
            Optional<ClaimedTask> candidate = tasks.stream()
                    .filter(predicate)
                    .filter(task -> maximumTasks - runningClaims.get()
                            >= minimumSlotsByTaskId.getOrDefault(task.taskInstanceId(), 1))
                    .findFirst();
            candidate.ifPresent(task -> {
                tasks.remove(task);
                claimedTaskIds.add(task.taskInstanceId());
                runningClaims.incrementAndGet();
            });
            return candidate;
        }

        @Override
        public ExecutionLease heartbeatExecution(ClaimedTask task) {
            return new ExecutionLease(Instant.now().plusSeconds(60), false);
        }

        @Override
        public void reportStep(ClaimedTask task, ClaimedStep step, int attempt, String status, Integer exitCode,
                               Map<String, Object> result, String errorCode, String errorMessage) {
        }

        @Override
        public void complete(ClaimedTask task, String status) {
            completions.incrementAndGet();
            runningClaims.decrementAndGet();
        }
    }

    private static final class DiskPressureSchedulerClient implements SchedulerClient {
        private int claimCalls;
        private int statusUpdates;
        private String lastStatus;
        private UUID lastExpectedInstanceId;

        @Override
        public ExecutorNodeRegistration register(UUID instanceId) {
            return new ExecutorNodeRegistration(UUID.randomUUID(), "executor-test", instanceId.toString());
        }

        @Override
        public void heartbeat(UUID nodeId, UUID instanceId, int runningTasks) {
        }

        @Override
        public void updateNodeStatus(UUID nodeId, UUID expectedInstanceId, String status, String reason) {
            statusUpdates++;
            lastStatus = status;
            lastExpectedInstanceId = expectedInstanceId;
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
