package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TaskDispatchDeadlineServiceTest {

    @Autowired
    private TaskInstanceService taskInstanceService;

    @Autowired
    private TaskDefinitionService taskDefinitionService;

    @Autowired
    private TaskStepService taskStepService;

    @Autowired
    private ExecutionTopologyService executionTopologyService;

    @Autowired
    private TaskDispatchService taskDispatchService;

    @Autowired
    private TaskDeadlineService taskDeadlineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndExpireUnclaimedTaskWithoutExecutor() {
        TestTask testTask = createTask(120, Map.of());
        Timestamp persistedDeadline = jdbcTemplate.queryForObject(
                "SELECT dispatch_deadline_at FROM task_instance WHERE id = ?",
                Timestamp.class, testTask.task().id().toString());
        assertNotNull(persistedDeadline);
        assertTrue(persistedDeadline.toInstant().isAfter(testTask.task().createdAt()));

        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)), testTask.task().id().toString());

        assertTrue(taskDeadlineService.expireDeadlines(now) >= 1);
        assertEquals(TaskStatus.TIMED_OUT, taskInstanceService.get(testTask.task().id()).status());
        assertEquals(0, countExecutions(testTask.task().id()));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_outbox
                WHERE aggregate_id = ? AND event_type = 'TaskTimedOut'
                """, Integer.class, testTask.task().id().toString()));
    }

    @Test
    void shouldBackfillAndExpireLegacyUnclaimedTask() {
        TestTask testTask = createTask(120, Map.of());
        Instant now = Instant.now();
        Instant legacyCreatedAt = now.minusSeconds(301);
        jdbcTemplate.update("""
                UPDATE task_instance
                SET created_at = ?, dispatch_deadline_at = NULL
                WHERE id = ?
                """, Timestamp.from(legacyCreatedAt), testTask.task().id().toString());

        assertTrue(taskDeadlineService.expireDeadlines(now) >= 1);

        Timestamp deadline = jdbcTemplate.queryForObject(
                "SELECT dispatch_deadline_at FROM task_instance WHERE id = ?",
                Timestamp.class, testTask.task().id().toString());
        assertNotNull(deadline);
        assertEquals(legacyCreatedAt.plusSeconds(300).toEpochMilli(), deadline.toInstant().toEpochMilli());
        assertEquals(TaskStatus.TIMED_OUT, taskInstanceService.get(testTask.task().id()).status());
    }

    @Test
    void shouldExpireTaskWhenAvailableExecutorDoesNotMatchLabels() {
        TestTask testTask = createTask(120, Map.of("zone", "required"));
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-label-node-" + testTask.suffix(), instanceId.toString(), Map.of(),
                Map.of("zone", "other"), 1, Set.of(testTask.clusterName())));

        assertTrue(taskDispatchService.claim(new ClaimTaskRequest(node.id(), instanceId, 60)).isEmpty());
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(1)), testTask.task().id().toString());

        assertTrue(taskDeadlineService.expireDeadlines(now) >= 1);
        assertEquals(TaskStatus.TIMED_OUT, taskInstanceService.get(testTask.task().id()).status());
    }

    @Test
    void shouldScanPastFirstCandidateBatchForMatchingLabels() {
        TestTask matchingTask = createTask(120, Map.of("zone", "matching"));
        for (int index = 0; index < 32; index++) {
            String blockerId = matchingTask.suffix() + "_blocker_" + index;
            taskInstanceService.create(new CreateTaskRequest(
                    matchingTask.task().taskName(), "dispatch_blocker_" + blockerId,
                    "TEST", blockerId, null, 90, Map.of(), Map.of("zone", "other")));
        }
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-paged-node-" + matchingTask.suffix(), instanceId.toString(), Map.of(),
                Map.of("zone", "matching"), 1, Set.of(matchingTask.clusterName())));

        var claimed = taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();

        assertEquals(matchingTask.task().id(), claimed.taskInstanceId());
    }

    @Test
    void shouldScanPastFirstMultiNodeCandidateBatchForMatchingLabels() {
        TestTask matchingTask = createTask(
                120, Map.of("zone", "matching"), ExecutionMode.MULTI_NODE_SHARD);
        for (int index = 0; index < 32; index++) {
            String blockerId = matchingTask.suffix() + "_multi_blocker_" + index;
            taskInstanceService.create(new CreateTaskRequest(
                    matchingTask.task().taskName(), "dispatch_multi_blocker_" + blockerId,
                    "TEST", blockerId, null, 90, Map.of(), Map.of("zone", "other")));
        }
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-paged-multi-node-" + matchingTask.suffix(), instanceId.toString(), Map.of(),
                Map.of("zone", "matching"), 1, Set.of(matchingTask.clusterName())));

        var claimed = taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();

        assertEquals(matchingTask.task().id(), claimed.taskInstanceId());
    }

    @Test
    void shouldAllowOnlyClaimOrDispatchDeadlineToWin() throws Exception {
        TestTask testTask = createTask(120, Map.of());
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-race-node-" + testTask.suffix(), instanceId.toString(), Map.of(), Map.of(),
                1, Set.of(testTask.clusterName())));
        Instant deadline = Instant.now().plusSeconds(2);
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(deadline), testTask.task().id().toString());
        CountDownLatch start = new CountDownLatch(1);

        Optional<?> claimed;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claim = executor.submit(() -> {
                start.await();
                return taskDispatchService.claim(new ClaimTaskRequest(node.id(), instanceId, 60));
            });
            var expire = executor.submit(() -> {
                start.await();
                return taskDeadlineService.expireDeadlines(deadline.plusSeconds(1));
            });
            start.countDown();
            claimed = claim.get();
            expire.get();
        }

        TaskStatus status = taskInstanceService.get(testTask.task().id()).status();
        assertTrue(status == TaskStatus.RUNNING || status == TaskStatus.TIMED_OUT);
        assertEquals(status == TaskStatus.RUNNING, claimed.isPresent());
        assertEquals(status == TaskStatus.RUNNING ? 1 : 0, countExecutions(testTask.task().id()));
        assertFalse(status == TaskStatus.TIMED_OUT && claimed.isPresent());
    }

    @Test
    void shouldKeepExecutionDeadlineIndependentFromQueueDeadline() {
        TestTask testTask = createTask(3600, Map.of());
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-normal-node-" + testTask.suffix(), instanceId.toString(), Map.of(), Map.of(),
                1, Set.of(testTask.clusterName())));
        Instant shortQueueDeadline = Instant.now().plusSeconds(5);
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(shortQueueDeadline), testTask.task().id().toString());

        Instant beforeClaim = Instant.now();
        var claimed = taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();

        assertTrue(claimed.deadlineAt().isAfter(beforeClaim.plusSeconds(3590)));
        taskDeadlineService.expireDeadlines(shortQueueDeadline.plusSeconds(1));
        assertEquals(TaskStatus.RUNNING, taskInstanceService.get(testTask.task().id()).status());
    }

    private TestTask createTask(long executionTimeoutSeconds, Map<String, Object> requiredLabels) {
        return createTask(executionTimeoutSeconds, requiredLabels, ExecutionMode.SINGLE_NODE);
    }

    private TestTask createTask(long executionTimeoutSeconds, Map<String, Object> requiredLabels,
                                ExecutionMode executionMode) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String clusterName = "dispatch-deadline-cluster-" + suffix;
        var cluster = executionTopologyService.createCluster(new CreateExecutionClusterRequest(
                clusterName, "Dispatch deadline workers", "LEAST_RUNNING", 2, Map.of(), true));
        var definition = taskDefinitionService.create(new CreateTaskDefinitionRequest(
                "dispatch_deadline_task_" + suffix, "Dispatch deadline task", TaskType.IMMEDIATE,
                executionTimeoutSeconds, cluster.id(), null, null, executionMode, true,
                2, "QUEUE", "IGNORE", Map.of(), Map.of()));
        taskStepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run dispatch deadline task", StepKind.NORMAL, "dispatch_deadline", "1.0.0",
                "scripts/main.py", List.of(), true, Math.min(executionTimeoutSeconds, 600),
                FailurePolicy.FAIL_TASK, 10, 1));
        TaskInstanceView task = taskInstanceService.create(new CreateTaskRequest(
                definition.name(), "dispatch_deadline_" + suffix, "TEST", suffix, null, 50, Map.of(),
                requiredLabels));
        return new TestTask(suffix, clusterName, task);
    }

    private int countExecutions(UUID taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_instance_id = ?",
                Integer.class, taskId.toString());
        return count == null ? 0 : count;
    }

    private record TestTask(String suffix, String clusterName, TaskInstanceView task) {
    }
}
