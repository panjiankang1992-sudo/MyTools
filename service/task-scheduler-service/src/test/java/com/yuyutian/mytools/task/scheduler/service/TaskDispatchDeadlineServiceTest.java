package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
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
import org.springframework.context.annotation.Import;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(QueueDispatchTestSchema.class)
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

    @Autowired
    private TaskScriptApiService scriptApiService;

    @Autowired
    private TaskCancellationPropagationService cancellationPropagationService;

    @Test
    void shouldKeepNewUnclaimedTaskQueuedWithoutDeadline() {
        TestTask testTask = createTask(120, Map.of());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT dispatch_deadline_at FROM task_instance WHERE id = ?",
                Timestamp.class, testTask.task().id().toString()));
        taskDeadlineService.expireDeadlines(Instant.now().plusSeconds(30L * 86400));
        assertEquals(TaskStatus.QUEUED, taskInstanceService.get(testTask.task().id()).status());
        assertEquals(0, countExecutions(testTask.task().id()));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_outbox WHERE aggregate_id = ? AND event_type = 'TaskTimedOut'
                """, Integer.class, testTask.task().id().toString()));
    }

    @Test
    void shouldClaimLegacyTaskEvenWhenItsStoredQueueDeadlineIsExpired() {
        TestTask testTask = createTask(120, Map.of());
        Instant old = Instant.now().minusSeconds(30L * 86400);
        jdbcTemplate.update("UPDATE task_instance SET created_at = ?, dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(old), Timestamp.from(old.plusSeconds(300)), testTask.task().id().toString());
        taskDeadlineService.expireDeadlines(Instant.now());
        assertEquals(TaskStatus.QUEUED, taskInstanceService.get(testTask.task().id()).status());
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "legacy-queue-node-" + testTask.suffix(), instanceId.toString(), Map.of(), Map.of(),
                1, Set.of(testTask.clusterName())));
        assertEquals(testTask.task().id(), taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow().taskInstanceId());
    }

    @Test
    void shouldKeepWaitingForMatchingLabelsWithoutExpiring() {
        TestTask testTask = createTask(120, Map.of("zone", "required"));
        UUID instanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "dispatch-label-node-" + testTask.suffix(), instanceId.toString(), Map.of(),
                Map.of("zone", "other"), 1, Set.of(testTask.clusterName())));
        assertTrue(taskDispatchService.claim(new ClaimTaskRequest(node.id(), instanceId, 60)).isEmpty());
        taskDeadlineService.expireDeadlines(Instant.now().plusSeconds(30L * 86400));
        assertEquals(TaskStatus.QUEUED, taskInstanceService.get(testTask.task().id()).status());
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

        // 多节点与单节点都必须接受遗留的过期排队字段。
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(86400)), matchingTask.task().id().toString());
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

        // 多节点与单节点都必须接受遗留的过期排队字段。
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(86400)), matchingTask.task().id().toString());
        var claimed = taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();

        assertEquals(matchingTask.task().id(), claimed.taskInstanceId());
    }

    @Test
    void shouldAllowClaimWhileQueueAgeScanRuns() throws Exception {
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
        assertEquals(TaskStatus.RUNNING, status);
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
        Instant shortQueueDeadline = Instant.now().minusSeconds(86400);
        jdbcTemplate.update("UPDATE task_instance SET dispatch_deadline_at = ? WHERE id = ?",
                Timestamp.from(shortQueueDeadline), testTask.task().id().toString());

        Instant beforeClaim = Instant.now();
        var claimed = taskDispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, 60)).orElseThrow();

        assertTrue(claimed.deadlineAt().isAfter(beforeClaim.plusSeconds(3590)));
        taskDeadlineService.expireDeadlines(shortQueueDeadline.plusSeconds(1));
        assertEquals(TaskStatus.RUNNING, taskInstanceService.get(testTask.task().id()).status());
    }

    @Test
    void shouldStillExpireRetryAfterExecutionAlreadyStarted() {
        TestTask testTask = createTask(120, Map.of());
        Instant now = Instant.now();
        // 重试重新排队仍属于已启动的同一次执行预算，不能无限延长执行期限。
        jdbcTemplate.update("UPDATE task_instance SET started_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(121)), testTask.task().id().toString());
        taskDeadlineService.expireDeadlines(now);
        assertEquals(TaskStatus.TIMED_OUT, taskInstanceService.get(testTask.task().id()).status());
    }

    @Test
    void shouldAllowCancellingAnOldQueuedTask() {
        TestTask testTask = createTask(120, Map.of());
        jdbcTemplate.update("UPDATE task_instance SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(30L * 86400)), testTask.task().id().toString());
        taskInstanceService.cancel(testTask.task().id());
        assertEquals(TaskStatus.CANCELLED, taskInstanceService.get(testTask.task().id()).status());
    }

    @Test
    void shouldCancelActiveDownloadChildrenAfterBatchExecutionTimesOut() {
        String suffix = UUID.randomUUID().toString();
        String batchName = "download_message_url_batch";
        String clusterName = jdbcTemplate.queryForObject("""
                SELECT cluster.name FROM execution_cluster cluster
                JOIN task_definition definition ON definition.cluster_id = cluster.id
                WHERE definition.name = ?
                """, String.class, batchName);
        UUID nodeInstanceId = UUID.randomUUID();
        var node = executionTopologyService.registerNode(new RegisterExecutorNodeRequest(
                "download-timeout-" + suffix, nodeInstanceId.toString(), Map.of(), Map.of(), 12,
                Set.of(clusterName)));
        var parent = taskInstanceService.create(new CreateTaskRequest(batchName, "download-timeout:" + suffix,
                "DOWNLOAD_REQUEST", suffix, null, 99,
                Map.of("downloadRequestId", suffix, "ownerId", 7, "messageBatchId", suffix,
                        "receivedAt", Instant.now().toString(),
                        "items", List.of(Map.of("url", "https://example.com/a.jpg", "fileName", "a.jpg")))));
        var parentExecution = taskDispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(parent.id(), parentExecution.taskInstanceId());
        var child = scriptApiService.createChild(parentExecution.executionId(), new CreateChildTaskRequest(
                parentExecution.leaseToken(), "download_resolve_x_url", "download-timeout-child:" + suffix,
                "DOWNLOAD_REQUEST", suffix, 50, Map.of("url", "https://x.com/example/status/123",
                "resolveOnly", true), Map.of()));
        var complete = new CompleteExecutionRequest(parentExecution.leaseToken(), TaskStatus.TIMED_OUT);

        taskDispatchService.complete(parentExecution.executionId(), complete);
        taskDispatchService.complete(parentExecution.executionId(), complete);
        cancellationPropagationService.processPendingBatch();

        assertEquals(TaskStatus.TIMED_OUT, taskInstanceService.get(parent.id()).status());
        assertEquals(TaskStatus.CANCELLED, taskInstanceService.get(child.id()).status());
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
