package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.config.TaskOutboxHealthIndicator;
import com.yuyutian.mytools.task.scheduler.config.ExecutorNodeHealthIndicator;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CompensationStatus;
import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.WriteTaskCheckpointRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import com.yuyutian.mytools.task.scheduler.model.NodeStatus;
import com.yuyutian.mytools.task.scheduler.model.UpdateExecutorNodeStatusRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TaskInstanceServiceTest {

    @Autowired
    private TaskInstanceService service;

    @Autowired
    private TaskDefinitionService definitionService;

    @Autowired
    private TaskStepService stepService;

    @Autowired
    private ExecutionTopologyService topologyService;

    @Autowired
    private TaskDispatchService dispatchService;

    @Autowired
    private TaskScriptApiService scriptApiService;

    @Autowired
    private TaskLeaseRecoveryService leaseRecoveryService;

    @Autowired
    private TaskResultQueryService resultQueryService;

    @Autowired
    private CronTaskTriggerService cronTaskTriggerService;

    @Autowired
    private TaskDeadlineService taskDeadlineService;

    @Autowired
    private TaskEventService taskEventService;

    @Autowired
    private TaskCancellationPropagationService cancellationPropagationService;

    @Autowired
    private TaskCheckpointService checkpointService;

    @Autowired
    private TaskOutboxMonitor taskOutboxMonitor;

    @Autowired
    private TaskOutboxHealthIndicator taskOutboxHealthIndicator;

    @Autowired
    private ExecutorNodeHealthService executorNodeHealthService;

    @Autowired
    private ExecutorNodeMonitor executorNodeMonitor;

    @Autowired
    private ExecutorNodeHealthIndicator executorNodeHealthIndicator;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeBlockedClusterHealthAndRecoverAvailabilityAfterNodeRegistration() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "unavailable_cluster_" + suffix, "Unavailable cluster", "LEAST_RUNNING", 2, Map.of(), true));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "blocked_task_" + suffix, "Blocked task", TaskType.IMMEDIATE, 120, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "SKIP", "IGNORE", Map.of(), Map.of()));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run", StepKind.NORMAL, "sample", "1.0.0", "main.py", List.of(), true,
                30, FailurePolicy.FAIL_TASK, 10, 1));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "blocked_" + suffix, "TEST", suffix, null, 1, Map.of()));
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE task_instance SET created_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(120)), task.id().toString());

        var blocked = executorNodeMonitor.snapshot(now);
        assertTrue(blocked.unavailableClusters().contains(cluster.name()));
        assertTrue(blocked.blockedQueuedTasks() >= 1);
        assertEquals("DOWN", executorNodeHealthIndicator.health(now).getStatus().getCode());
        assertNotNull(meterRegistry.find("task.executor.tasks.blocked").gauge());

        UUID instanceId = UUID.randomUUID();
        topologyService.registerNode(new RegisterExecutorNodeRequest(
                "recovery-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 1, Set.of(cluster.name())));

        var recovered = executorNodeMonitor.snapshot(Instant.now());
        assertFalse(recovered.unavailableClusters().contains(cluster.name()));
    }

    @Test
    void shouldPreserveDrainingStateAcrossHeartbeatAndRejectNewClaims() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID instanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "draining-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 1, Set.of()));

        topologyService.updateNodeStatus(node.id(),
                new UpdateExecutorNodeStatusRequest(NodeStatus.DRAINING, "maintenance"));
        var heartbeatNode = topologyService.heartbeat(node.id(), instanceId.toString(), 0);

        assertEquals(NodeStatus.DRAINING, heartbeatNode.status());
        assertThrows(IllegalArgumentException.class, () -> dispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, UUID.randomUUID(), 60)));
    }

    @Test
    void shouldMarkStaleNodeOfflineAndRecoverOnHeartbeat() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID instanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "stale-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 1, Set.of()));
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE executor_node SET last_heartbeat_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(61)), node.id().toString());

        assertEquals(1, executorNodeHealthService.scanOfflineNodes(now));
        assertEquals(NodeStatus.OFFLINE, topologyService.listNodes().stream()
                .filter(candidate -> candidate.id().equals(node.id())).findFirst().orElseThrow().status());
        assertThrows(IllegalArgumentException.class, () -> dispatchService.claim(
                new ClaimTaskRequest(node.id(), instanceId, UUID.randomUUID(), 60)));

        assertEquals(NodeStatus.ONLINE,
                topologyService.heartbeat(node.id(), instanceId.toString(), 0).status());
    }

    @Test
    void shouldCreateIdempotentlyAndCancel() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskName = "media_generate_tags_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                taskName, "Generate media tags", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        CreateTaskRequest request = new CreateTaskRequest(
                taskName, "asset_1_v1_" + suffix, "MEDIA_ASSET", "1", null, 50, Map.of("assetId", "1")
        );

        var first = service.create(request);
        var second = service.create(request);

        assertEquals(first.id(), second.id());
        assertEquals(TaskStatus.CANCELLED, service.cancel(first.id()).status());
        assertEquals(List.of("TaskQueued", "TaskCancellationRequested", "TaskCancelled"),
                jdbcTemplate.queryForList("""
                        SELECT event_type FROM task_event
                        WHERE task_instance_id = ? ORDER BY created_at, event_type
                        """, String.class, first.id().toString()));
    }

    @Test
    void shouldValidateParametersAndRejectIdempotencyPayloadConflicts() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskName = "contract_task_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                taskName, "Contract task", TaskType.IMMEDIATE, 60, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "SKIP", "IGNORE",
                Map.of("type", "object", "required", List.of("assetId"), "properties",
                        Map.of("assetId", Map.of("type", "string", "minLength", 1)),
                        "additionalProperties", false), Map.of()));

        assertThrows(SchedulerException.class, () -> service.create(new CreateTaskRequest(
                taskName, "contract_" + suffix, "TEST", "1", null, 50, Map.of())));
        CreateTaskRequest valid = new CreateTaskRequest(
                taskName, "contract_" + suffix, "TEST", "1", null, 50, Map.of("assetId", "asset-1"));
        service.create(valid);
        assertThrows(SchedulerException.class, () -> service.create(new CreateTaskRequest(
                taskName, "contract_" + suffix, "TEST", "1", null, 50, Map.of("assetId", "asset-2"))));
    }

    @Test
    void shouldCascadeParentCancellationToQueuedChildren() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String parentName = "parent_cancel_" + suffix;
        String childName = "child_cancel_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                parentName, "Parent cancellation", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 2, "SKIP", "IGNORE", Map.of(), Map.of()));
        definitionService.create(new CreateTaskDefinitionRequest(
                childName, "Child cancellation", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 2, "SKIP", "IGNORE", Map.of(), Map.of()));
        var parent = service.create(new CreateTaskRequest(
                parentName, "parent_cancel_key_" + suffix, "TEST", "parent", null, 50, Map.of()));
        var child = service.create(new CreateTaskRequest(
                childName, "child_cancel_key_" + suffix, "TEST", "child", parent.id(), 50, Map.of()));

        assertEquals(TaskStatus.CANCELLED, service.cancel(parent.id()).status());
        assertEquals(TaskStatus.CANCELLED, service.get(child.id()).status());
    }

    @Test
    void shouldPropagateLargeTaskTreeCancellationInBoundedBatches() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskName = "bounded_cancel_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                taskName, "Bounded cancellation", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 200, "SKIP", "IGNORE", Map.of(), Map.of()));
        var parent = service.create(new CreateTaskRequest(
                taskName, "bounded_parent_" + suffix, "TEST", "parent", null, 50, Map.of()));
        for (int index = 0; index < 101; index++) {
            service.create(new CreateTaskRequest(
                    taskName, "bounded_child_" + suffix + "_" + index, "TEST", "child-" + index,
                    parent.id(), 50, Map.of()));
        }

        assertEquals(TaskStatus.CANCELLING, service.cancel(parent.id()).status());
        Integer processed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_cancellation_queue
                WHERE root_task_instance_id = ? AND status = 'PROCESSED'
                """, Integer.class, parent.id().toString());
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_cancellation_queue
                WHERE root_task_instance_id = ? AND status = 'PENDING'
                """, Integer.class, parent.id().toString());
        assertEquals(100, processed);
        assertEquals(1, pending);

        assertEquals(1, cancellationPropagationService.processPendingBatch());
        assertEquals(TaskStatus.CANCELLED, service.get(parent.id()).status());
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_instance
                WHERE parent_task_instance_id = ? AND status <> 'CANCELLED'
                """, Integer.class, parent.id().toString()));
    }

    @Test
    void shouldRejectChildCreationBeyondMaximumTaskDepth() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskName = "depth_limit_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                taskName, "Depth limit", TaskType.IMMEDIATE, 600, null, null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()));
        UUID parentId = null;
        for (int depth = 1; depth <= 8; depth++) {
            var task = service.create(new CreateTaskRequest(
                    taskName, "depth_" + suffix + "_" + depth, "TEST", "depth-" + depth,
                    parentId, 50, Map.of()));
            parentId = task.id();
        }
        UUID deepestTaskId = parentId;

        assertThrows(IllegalStateException.class, () -> service.create(new CreateTaskRequest(
                taskName, "depth_" + suffix + "_9", "TEST", "depth-9",
                deepestTaskId, 50, Map.of())));

        UUID rootId = jdbcTemplate.queryForObject("""
                SELECT id FROM task_instance
                WHERE idempotency_key = ?
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString(1)),
                "depth_" + suffix + "_1");
        assertEquals(TaskStatus.CANCELLING, service.cancel(rootId).status());
        while (cancellationPropagationService.processPendingBatch() > 0) {
            // 持久队列逐批推进，验证合法的第八层叶子不会被误判为第九层子任务。
        }
        assertEquals(TaskStatus.CANCELLED, service.get(deepestTaskId).status());
        assertEquals(TaskStatus.CANCELLED, service.get(rootId).status());
    }

    @Test
    void shouldWaitForChildrenBeforeCompletingParentTask() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "child_wait_cluster_" + suffix, "Child wait workers", "LEAST_RUNNING", 2, Map.of(), true));
        UUID nodeInstanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "child-wait-node-" + suffix, nodeInstanceId.toString(), Map.of("shell", true), Map.of(), 2,
                Set.of(cluster.name())));
        String parentName = "child_wait_parent_" + suffix;
        String childName = "child_wait_child_" + suffix;
        var parentDefinition = definitionService.create(new CreateTaskDefinitionRequest(
                parentName, "Parent wait", TaskType.IMMEDIATE, 600, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()));
        var childDefinition = definitionService.create(new CreateTaskDefinitionRequest(
                childName, "Child wait", TaskType.IMMEDIATE, 600, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()));
        for (var definition : List.of(parentDefinition, childDefinition)) {
            stepService.create(definition.id(), new CreateTaskStepRequest(
                    "run", "Run", StepKind.NORMAL, "sample", "1.0.0", "main.py", List.of(), true,
                    30, FailurePolicy.FAIL_TASK, 10, 1));
        }
        var parent = service.create(new CreateTaskRequest(
                parentName, "child_wait_parent_key_" + suffix, "TEST", "parent", null, 50, Map.of()));
        var parentExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        var child = scriptApiService.createChild(parentExecution.executionId(), new CreateChildTaskRequest(
                parentExecution.leaseToken(), childName, "child_wait_child_key_" + suffix,
                "TEST", "child", 50, Map.of(), Map.of()));

        dispatchService.complete(parentExecution.executionId(),
                new CompleteExecutionRequest(parentExecution.leaseToken(), TaskStatus.SUCCEEDED));
        assertEquals(TaskStatus.WAITING_CHILDREN, service.get(parent.id()).status());

        var childExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(child.id(), childExecution.taskInstanceId());
        dispatchService.complete(childExecution.executionId(),
                new CompleteExecutionRequest(childExecution.leaseToken(), TaskStatus.SUCCEEDED));
        assertEquals(TaskStatus.SUCCEEDED, service.get(child.id()).status());
        assertEquals(TaskStatus.SUCCEEDED, service.get(parent.id()).status());
    }

    @Test
    void shouldRecoverAndAdvanceCheckpointWithNewExecutionFence() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "checkpoint_cluster_" + suffix, "Checkpoint workers", "LEAST_RUNNING", 2, Map.of(), true));
        UUID nodeInstanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "checkpoint-node-" + suffix, nodeInstanceId.toString(), Map.of("shell", true), Map.of(), 1,
                Set.of(cluster.name())));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "checkpoint_task_" + suffix, "Checkpoint task", TaskType.IMMEDIATE, 600,
                cluster.id(), null, null, ExecutionMode.SINGLE_NODE, true, 3,
                "SKIP", "IGNORE", Map.of(), Map.of()));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run", StepKind.NORMAL, "sample", "1.0.0", "main.py", List.of(), true,
                30, FailurePolicy.FAIL_TASK, 10, 1));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "checkpoint_key_" + suffix, "TEST", suffix, null, 50, Map.of()));
        var firstExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        UUID firstRequestId = UUID.randomUUID();
        var firstWrite = new WriteTaskCheckpointRequest(firstRequestId, 0, Map.of("offset", 100));

        assertEquals(1, checkpointService.write(firstExecution.executionId(), firstExecution.leaseToken(),
                "scan.cursor", firstWrite).version());
        assertTrue(checkpointService.write(firstExecution.executionId(), firstExecution.leaseToken(),
                "scan.cursor", firstWrite).replayed());
        assertThrows(SchedulerException.class, () -> checkpointService.write(
                firstExecution.executionId(), firstExecution.leaseToken(), "scan.cursor",
                new WriteTaskCheckpointRequest(firstRequestId, 0, Map.of("offset", 999))));
        assertThrows(SchedulerException.class, () -> checkpointService.write(
                firstExecution.executionId(), firstExecution.leaseToken(), "scan.cursor",
                new WriteTaskCheckpointRequest(UUID.randomUUID(), 0, Map.of("offset", 101))));
        assertThrows(IllegalArgumentException.class, () -> checkpointService.write(
                firstExecution.executionId(), firstExecution.leaseToken(), "../secret",
                new WriteTaskCheckpointRequest(UUID.randomUUID(), 0, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> checkpointService.write(
                firstExecution.executionId(), firstExecution.leaseToken(), "oversized",
                new WriteTaskCheckpointRequest(UUID.randomUUID(), 0, Map.of("data", "x".repeat(257 * 1024)))));
        assertEquals(1, checkpointService.list(
                firstExecution.executionId(), firstExecution.leaseToken()).size());

        jdbcTemplate.update("UPDATE task_execution SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), firstExecution.executionId().toString());
        assertEquals(1, leaseRecoveryService.recoverExpiredLeases());
        jdbcTemplate.update("UPDATE task_instance SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), task.id().toString());
        var secondExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();

        assertTrue(secondExecution.fencingToken() > firstExecution.fencingToken());
        assertTrue(checkpointService.write(secondExecution.executionId(), secondExecution.leaseToken(),
                "scan.cursor", firstWrite).replayed());
        assertEquals(Map.of("offset", 100), checkpointService.get(
                secondExecution.executionId(), secondExecution.leaseToken(), "scan.cursor").value());
        var advanced = checkpointService.write(secondExecution.executionId(), secondExecution.leaseToken(),
                "scan.cursor", new WriteTaskCheckpointRequest(UUID.randomUUID(), 1, Map.of("offset", 200)));
        assertEquals(2, advanced.version());
        assertEquals(Map.of("offset", 200), advanced.value());
        assertThrows(SchedulerException.class, () -> checkpointService.get(
                firstExecution.executionId(), firstExecution.leaseToken(), "scan.cursor"));
    }

    @Test
    void shouldPersistentlyTriggerOneMisfiredCronInstance() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "scheduled_probe_" + suffix, "Scheduled probe", TaskType.SCHEDULED, 60, null,
                "0 0 * * * *", "UTC", ExecutionMode.SINGLE_NODE, true, 1,
                "SKIP", "RUN_ONCE", Map.of(), Map.of()
        ));
        Instant future = definition.createdAt().plusSeconds(3 * 3600);

        cronTaskTriggerService.triggerDueTasks(future);
        cronTaskTriggerService.triggerDueTasks(future);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE task_definition_id = ?",
                Integer.class, definition.id().toString());
        String businessType = jdbcTemplate.queryForObject(
                "SELECT business_type FROM task_instance WHERE task_definition_id = ?",
                String.class, definition.id().toString());
        assertEquals(1, count);
        assertEquals("SCHEDULED_TASK", businessType);
    }

    @Test
    void shouldPersistStepsAndManyToManyExecutionTopology() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "media_cluster_" + suffix, "Media workers", "LEAST_RUNNING", 20, Map.of("gpu", true), true
        ));
        UUID nodeInstanceId = UUID.randomUUID();
        String scriptReleaseDigest = "a".repeat(64);
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "media-node-" + suffix, nodeInstanceId.toString(), Map.of(
                        "python", "3.12", "scriptReleases", Map.of("media_probe:1.0.0", scriptReleaseDigest)),
                Map.of("gpu", true), 4, Set.of(cluster.name())
        ));
        assertEquals(node.id(), topologyService.heartbeat(node.id(), nodeInstanceId.toString(), 1).id());

        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "media_probe_" + suffix, "Probe media", TaskType.IMMEDIATE, 120, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(),
                Map.of("type", "object", "required", List.of("duration"), "properties",
                        Map.of("duration", Map.of("type", "integer")), "additionalProperties", false)
        ));
        var step = stepService.create(definition.id(), new CreateTaskStepRequest(
                "probe", "Run ffprobe", StepKind.NORMAL, "media_probe", "1.0.0", "scripts/main.py",
                List.of("--asset-id", "${parameters.assetId}"), true, 90, FailurePolicy.FAIL_TASK, 10, 2
        ));

        var task = service.create(new CreateTaskRequest(
                definition.name(), "probe_" + suffix, "MEDIA_ASSET", "asset-1", null, 50, Map.of("assetId", "asset-1")
        ));
        UUID claimRequestId = UUID.randomUUID();
        var claimed = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, claimRequestId, 60)).orElseThrow();
        var replayedClaim = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, claimRequestId, 60)).orElseThrow();
        assertEquals(task.id(), claimed.taskInstanceId());
        assertEquals(claimed.executionId(), replayedClaim.executionId());
        Integer executionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE claim_request_id = ?",
                Integer.class, claimRequestId.toString());
        assertEquals(1, executionCount);
        assertTrue(claimed.fencingToken() > 0);
        assertEquals(1, claimed.steps().size());
        assertEquals(definition.id(), claimed.definitionId());
        assertEquals(definition.version(), claimed.definitionVersion());
        assertTrue(claimed.definitionDigest().matches("^[a-f0-9]{64}$"));
        assertEquals(scriptReleaseDigest, claimed.steps().getFirst().scriptReleaseDigest());
        String childTaskName = "child_probe_" + suffix;
        definitionService.create(new CreateTaskDefinitionRequest(
                childTaskName, "Child probe", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        var child = scriptApiService.createChild(claimed.executionId(), new CreateChildTaskRequest(
                claimed.leaseToken(), childTaskName, "child_" + suffix, "MEDIA_ASSET", "asset-2", 40, Map.of(),
                Map.of("storage.mount.managed", "present")
        ));
        assertEquals(task.id(), child.parentTaskInstanceId());
        assertEquals(Map.of("storage.mount.managed", "present"), child.requiredNodeLabels());
        assertEquals(child.id(), scriptApiService.getRelated(
                claimed.executionId(), claimed.leaseToken(), child.id()).id());
        assertEquals(child.id(), scriptApiService.getRelatedResults(
                claimed.executionId(), claimed.leaseToken(), child.id()).taskInstanceId());
        assertEquals(TaskStatus.CANCELLED, scriptApiService.cancelChild(
                claimed.executionId(), claimed.leaseToken(), child.id()).status());
        assertTrue(!dispatchService.heartbeat(claimed.executionId(),
                new LeaseHeartbeatRequest(claimed.leaseToken(), 60)).cancelRequested());
        UUID reportRequestId = UUID.randomUUID();
        assertThrows(SchedulerException.class, () -> dispatchService.reportStep(claimed.executionId(),
                new ReportStepExecutionRequest(UUID.randomUUID(), claimed.leaseToken(), step.id(), 1,
                        TaskStatus.SUCCEEDED, 0, Map.of("unexpected", true), null, null)));
        assertThrows(IllegalArgumentException.class, () -> dispatchService.reportStep(claimed.executionId(),
                new ReportStepExecutionRequest(UUID.randomUUID(), claimed.leaseToken(), step.id(), 1,
                        TaskStatus.SUCCEEDED, 0, Map.of("duration", 12), null, null,
                        Map.of("stdout", List.of(Map.of("path", "../secret", "sizeBytes", 1,
                                "sha256", "a".repeat(64)))))));
        ReportStepExecutionRequest stepReport = new ReportStepExecutionRequest(
                reportRequestId, claimed.leaseToken(), step.id(), 1, TaskStatus.SUCCEEDED, 0,
                Map.of("duration", 12), null, null,
                Map.of("stdout", List.of(Map.of("path", "stdout-000001.log", "sizeBytes", 12,
                        "sha256", "a".repeat(64))), "stderr", List.of()));
        assertEquals("accepted", dispatchService.reportStep(claimed.executionId(), stepReport).outcome());
        assertTrue(dispatchService.reportStep(claimed.executionId(), stepReport).replayed());
        String logIndex = jdbcTemplate.queryForObject(
                "SELECT log_index_json FROM step_execution WHERE report_request_id = ?",
                String.class, reportRequestId.toString());
        assertTrue(logIndex.contains("stdout-000001.log"));
        assertThrows(SchedulerException.class, () -> dispatchService.reportStep(claimed.executionId(),
                new ReportStepExecutionRequest(reportRequestId, claimed.leaseToken(), step.id(), 1,
                        TaskStatus.SUCCEEDED, 0, Map.of("duration", 13), null, null)));
        CompleteExecutionRequest completion = new CompleteExecutionRequest(
                UUID.randomUUID(), claimed.leaseToken(), TaskStatus.SUCCEEDED);
        assertEquals("accepted", dispatchService.complete(claimed.executionId(), completion).outcome());
        assertTrue(dispatchService.complete(claimed.executionId(), completion).replayed());

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_event WHERE task_instance_id = ?",
                Integer.class, task.id().toString());
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox WHERE aggregate_id = ?",
                Integer.class, task.id().toString());
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM task_outbox WHERE aggregate_id = ? AND event_type = 'TaskSucceeded'",
                String.class, task.id().toString());
        assertEquals(3, eventCount);
        assertEquals(3, outboxCount);
        assertFalse(payload.contains("assetId"));
        assertTrue(payload.contains("\"businessId\":\"asset-1\""));
        assertTrue(payload.contains("\"oldStatus\":\"RUNNING\""));
        assertTrue(payload.contains("\"newStatus\":\"SUCCEEDED\""));

        String terminalEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM task_outbox WHERE aggregate_id = ? AND event_type = 'TaskSucceeded'",
                String.class, task.id().toString());
        var outboxEvent = taskEventService.claimPending(1000, Instant.now()).stream()
                .filter(event -> event.id().toString().equals(terminalEventId))
                .findFirst().orElseThrow();
        taskEventService.markFailed(outboxEvent, "TestFailure", 2, Instant.now());
        assertEquals("RETRY", jdbcTemplate.queryForObject(
                "SELECT status FROM task_outbox WHERE id = ?", String.class, outboxEvent.id().toString()));
        jdbcTemplate.update("UPDATE task_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), outboxEvent.id().toString());
        var retriedEvent = taskEventService.claimPending(1000, Instant.now()).stream()
                .filter(event -> event.id().equals(outboxEvent.id())).findFirst().orElseThrow();
        taskEventService.markFailed(retriedEvent, "TestFailure", 2, Instant.now());
        assertEquals("DEAD", jdbcTemplate.queryForObject(
                "SELECT status FROM task_outbox WHERE id = ?", String.class, outboxEvent.id().toString()));
        assertTrue(taskOutboxMonitor.snapshot(Instant.now()).deadCount() >= 1);
        assertEquals("DOWN", taskOutboxHealthIndicator.health().getStatus().getCode());
        assertNotNull(meterRegistry.find("task.outbox.backlog").gauge());
        assertNotNull(meterRegistry.find("task.outbox.oldest.age.seconds").gauge());
        assertNotNull(meterRegistry.find("task.outbox.dead").gauge());
        assertEquals(1, taskEventService.replayEvent(outboxEvent.id(), Instant.now()));
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM task_outbox WHERE id = ?", String.class, outboxEvent.id().toString()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM task_outbox WHERE id = ?", Integer.class, outboxEvent.id().toString()));
        jdbcTemplate.update("UPDATE task_outbox SET status = 'DEAD' WHERE id = ?", outboxEvent.id().toString());
        assertEquals(1, taskEventService.replayTask(task.id(), Instant.now()));

        assertEquals(1, stepService.list(definition.id()).size());
        assertEquals(TaskStatus.SUCCEEDED, service.get(task.id()).status());
        var executionResult = resultQueryService.get(task.id());
        assertEquals(TaskStatus.SUCCEEDED, executionResult.status());
        assertEquals(Map.of("duration", 12), executionResult.steps().getFirst().result());
        assertTrue(topologyService.listClusters().stream().anyMatch(item -> item.id().equals(cluster.id())));
        assertTrue(topologyService.listNodes().stream().anyMatch(item -> item.id().equals(node.id())));
    }

    @Test
    void shouldPersistRequiredCompensationFailureAndPublishAttentionEvent() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "compensation_cluster_" + suffix, "Compensation workers", "LEAST_RUNNING", 10, Map.of(), true));
        UUID instanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "compensation-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 1,
                Set.of(cluster.name())));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "compensation_task_" + suffix, "Compensation task", TaskType.IMMEDIATE, 120, cluster.id(),
                null, null, ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run task", StepKind.NORMAL, "compensation", "1.0.0", "main.sh", List.of(), true,
                30, FailurePolicy.FAIL_TASK, 10, 1));
        var task = service.create(new CreateTaskRequest(definition.name(), "compensation_" + suffix,
                "COMPENSATION_TEST", "business-1", null, 50, Map.of()));
        var claimed = dispatchService.claim(new ClaimTaskRequest(
                node.id(), instanceId, UUID.randomUUID(), 60)).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> dispatchService.complete(claimed.executionId(),
                new CompleteExecutionRequest(UUID.randomUUID(), claimed.leaseToken(), TaskStatus.SUCCEEDED,
                        CompensationStatus.FAILED, true, "REMOTE_ROLLBACK_FAILED")));
        CompleteExecutionRequest completion = new CompleteExecutionRequest(UUID.randomUUID(),
                claimed.leaseToken(), TaskStatus.FAILED, CompensationStatus.FAILED, true,
                "REMOTE_ROLLBACK_FAILED");

        assertEquals("accepted", dispatchService.complete(claimed.executionId(), completion).outcome());
        assertTrue(dispatchService.complete(claimed.executionId(), completion).replayed());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT compensation_status FROM task_execution WHERE id = ?", String.class,
                claimed.executionId().toString()));
        assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT compensation_required FROM task_execution WHERE id = ?", Boolean.class,
                claimed.executionId().toString())));
        assertEquals("REMOTE_ROLLBACK_FAILED", jdbcTemplate.queryForObject(
                "SELECT compensation_error_code FROM task_execution WHERE id = ?", String.class,
                claimed.executionId().toString()));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_outbox
                WHERE aggregate_id = ? AND event_type = 'TaskCompensationRequired'
                """, Integer.class, task.id().toString()));
        var result = resultQueryService.get(task.id());
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals(CompensationStatus.FAILED, result.compensationStatus());
        assertTrue(result.compensationRequired());
        assertEquals("REMOTE_ROLLBACK_FAILED", result.compensationErrorCode());
    }

    @Test
    void shouldNotExceedNodeCapacityDuringConcurrentClaims() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "capacity_cluster_" + suffix, "Capacity workers", "LEAST_RUNNING", 10, Map.of(), true));
        UUID instanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "capacity-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 1, Set.of(cluster.name())));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "capacity_task_" + suffix, "Capacity task", TaskType.IMMEDIATE, 120, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run", StepKind.NORMAL, "capacity", "1.0.0", "main.sh", List.of(), true,
                60, FailurePolicy.FAIL_TASK, 10, 1));
        service.create(new CreateTaskRequest(
                definition.name(), "capacity_1_" + suffix, "TEST", "1", null, 50, Map.of()));
        service.create(new CreateTaskRequest(
                definition.name(), "capacity_2_" + suffix, "TEST", "2", null, 50, Map.of()));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return dispatchService.claim(new ClaimTaskRequest(node.id(), instanceId, UUID.randomUUID(), 60));
            });
            var second = executor.submit(() -> {
                start.await();
                return dispatchService.claim(new ClaimTaskRequest(node.id(), instanceId, UUID.randomUUID(), 60));
            });
            start.countDown();
            long claimedCount = java.util.stream.Stream.of(first.get(), second.get())
                    .filter(Optional::isPresent).count();
            assertEquals(1, claimedCount);
        }
        Integer runningCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE node_id = ? AND status = 'RUNNING'",
                Integer.class, node.id().toString());
        assertEquals(1, runningCount);
    }

    @Test
    void shouldEnforceDefinitionClusterAndNodeConcurrencyWhenClaiming() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "bounded_cluster_" + suffix, "Bounded workers", "LEAST_RUNNING", 1, Map.of(), true
        ));
        UUID nodeInstanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "bounded-node-" + suffix, nodeInstanceId.toString(), Map.of("shell", true), Map.of(), 1,
                Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "bounded_task_" + suffix, "Bounded task", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "QUEUE", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run bounded task", StepKind.NORMAL, "bounded_task", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        service.create(new CreateTaskRequest(
                definition.name(), "bounded_1_" + suffix, "TEST", "1", null, 50, Map.of()
        ));
        service.create(new CreateTaskRequest(
                definition.name(), "bounded_2_" + suffix, "TEST", "2", null, 50, Map.of()
        ));

        assertTrue(dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).isPresent());
        assertTrue(dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).isEmpty());
    }

    @Test
    void shouldClaimSingleNodeTaskOnlyOnMatchingNodeLabels() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "affinity_cluster_" + suffix, "Affinity workers", "LEAST_RUNNING", 2, Map.of(), true
        ));
        UUID firstInstanceId = UUID.randomUUID();
        UUID secondInstanceId = UUID.randomUUID();
        var firstNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "affinity-node-a-" + suffix, firstInstanceId.toString(), Map.of("shell", true),
                Map.of("storage.mount.library", "absent"), 1, Set.of(cluster.name())
        ));
        var secondNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "affinity-node-b-" + suffix, secondInstanceId.toString(), Map.of("shell", true),
                Map.of("storage.mount.library", "present"), 1, Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "affinity_task_" + suffix, "Affinity task", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "QUEUE", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run affinity task", StepKind.NORMAL, "affinity_task", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "affinity_" + suffix, "TEST", suffix, null, 50, Map.of(),
                Map.of("storage.mount.library", "present")
        ));

        assertTrue(dispatchService.claim(new ClaimTaskRequest(firstNode.id(), firstInstanceId, 60)).isEmpty());
        var claimed = dispatchService.claim(
                new ClaimTaskRequest(secondNode.id(), secondInstanceId, 60)).orElseThrow();

        assertEquals(task.id(), claimed.taskInstanceId());
        assertEquals(Map.of("storage.mount.library", "present"), service.get(task.id()).requiredNodeLabels());
        assertThrows(SchedulerException.class, () -> service.create(new CreateTaskRequest(
                definition.name(), "affinity_" + suffix, "TEST", suffix, null, 50, Map.of(),
                Map.of("storage.mount.library", "other")
        )));
    }

    @Test
    void shouldExpandMultiNodeTaskOnlyToMatchingNodes() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "affinity_broadcast_" + suffix, "Affinity broadcast", "LEAST_RUNNING", 2, Map.of(), true
        ));
        UUID firstInstanceId = UUID.randomUUID();
        UUID secondInstanceId = UUID.randomUUID();
        var firstNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "affinity-broadcast-a-" + suffix, firstInstanceId.toString(), Map.of("shell", true),
                Map.of("zone", "cold"), 1, Set.of(cluster.name())
        ));
        var secondNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "affinity-broadcast-b-" + suffix, secondInstanceId.toString(), Map.of("shell", true),
                Map.of("zone", "hot"), 1, Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "affinity_broadcast_task_" + suffix, "Affinity broadcast task", TaskType.IMMEDIATE, 60,
                cluster.id(), null, null, ExecutionMode.MULTI_NODE_BROADCAST, true, 1,
                "QUEUE", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run affinity broadcast", StepKind.NORMAL, "affinity_broadcast", "1.0.0",
                "scripts/main.sh", List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        service.create(new CreateTaskRequest(
                definition.name(), "affinity_broadcast_" + suffix, "TEST", suffix, null, 50, Map.of(),
                Map.of("zone", "hot")
        ));

        assertTrue(dispatchService.claim(new ClaimTaskRequest(firstNode.id(), firstInstanceId, 60)).isEmpty());
        var claimed = dispatchService.claim(
                new ClaimTaskRequest(secondNode.id(), secondInstanceId, 60)).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) claimed.parameters().get("taskExecutionTarget");

        assertEquals(1, target.get("count"));
        assertEquals(secondNode.id().toString(), target.get("nodeId"));
    }

    @Test
    void shouldExpandShardTaskAcrossNodesAndAggregateSuccess() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "shard_cluster_" + suffix, "Shard workers", "LEAST_RUNNING", 4, Map.of(), true
        ));
        UUID firstInstanceId = UUID.randomUUID();
        UUID secondInstanceId = UUID.randomUUID();
        var firstNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "shard-node-a-" + suffix, firstInstanceId.toString(), Map.of("shell", true), Map.of(), 2,
                Set.of(cluster.name())
        ));
        var secondNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "shard-node-b-" + suffix, secondInstanceId.toString(), Map.of("shell", true), Map.of(), 2,
                Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "shard_task_" + suffix, "Shard task", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.MULTI_NODE_SHARD, true, 1, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        var step = stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run shard", StepKind.NORMAL, "shard_task", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "shard_" + suffix, "TEST", suffix, null, 50, Map.of("input", "value")
        ));

        var first = dispatchService.claim(new ClaimTaskRequest(firstNode.id(), firstInstanceId, 60)).orElseThrow();
        var second = dispatchService.claim(new ClaimTaskRequest(secondNode.id(), secondInstanceId, 60)).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> firstTarget = (Map<String, Object>) first.parameters().get("taskExecutionTarget");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondTarget = (Map<String, Object>) second.parameters().get("taskExecutionTarget");
        assertEquals(2, firstTarget.get("count"));
        assertEquals(2, secondTarget.get("count"));
        assertTrue(!firstTarget.get("index").equals(secondTarget.get("index")));

        dispatchService.reportStep(first.executionId(), new ReportStepExecutionRequest(
                first.leaseToken(), step.id(), 1, TaskStatus.SUCCEEDED, 0, Map.of("rows", 3), null, null
        ));
        dispatchService.reportStep(second.executionId(), new ReportStepExecutionRequest(
                second.leaseToken(), step.id(), 1, TaskStatus.SUCCEEDED, 0, Map.of("rows", 4), null, null
        ));
        dispatchService.complete(first.executionId(),
                new CompleteExecutionRequest(first.leaseToken(), TaskStatus.SUCCEEDED));
        assertEquals(TaskStatus.RUNNING, service.get(task.id()).status());
        dispatchService.complete(second.executionId(),
                new CompleteExecutionRequest(second.leaseToken(), TaskStatus.SUCCEEDED));
        assertEquals(TaskStatus.SUCCEEDED, service.get(task.id()).status());
        var results = resultQueryService.get(task.id());
        assertEquals(2, results.steps().size());
        assertEquals(2, results.steps().getFirst().targetCount());
        assertTrue(results.steps().stream().map(item -> item.targetIndex()).distinct().count() == 2);
    }

    @Test
    void shouldCancelQueuedAndRunningBroadcastTargets() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "broadcast_cluster_" + suffix, "Broadcast workers", "LEAST_RUNNING", 4, Map.of(), true
        ));
        UUID firstInstanceId = UUID.randomUUID();
        var firstNode = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "broadcast-node-a-" + suffix, firstInstanceId.toString(), Map.of("shell", true), Map.of(), 2,
                Set.of(cluster.name())
        ));
        topologyService.registerNode(new RegisterExecutorNodeRequest(
                "broadcast-node-b-" + suffix, UUID.randomUUID().toString(), Map.of("shell", true), Map.of(), 2,
                Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "broadcast_task_" + suffix, "Broadcast task", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.MULTI_NODE_BROADCAST, true, 1, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run broadcast", StepKind.NORMAL, "broadcast_task", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "broadcast_" + suffix, "TEST", suffix, null, 50, Map.of()
        ));
        var running = dispatchService.claim(
                new ClaimTaskRequest(firstNode.id(), firstInstanceId, 60)).orElseThrow();

        assertEquals(TaskStatus.CANCELLING, service.cancel(task.id()).status());
        assertTrue(dispatchService.heartbeat(running.executionId(),
                new LeaseHeartbeatRequest(running.leaseToken(), 60)).cancelRequested());
        dispatchService.complete(running.executionId(),
                new CompleteExecutionRequest(running.leaseToken(), TaskStatus.CANCELLED));
        assertEquals(TaskStatus.CANCELLED, service.get(task.id()).status());
    }

    @Test
    void shouldRequeueOnlyExpiredShardTarget() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "shard_recovery_cluster_" + suffix, "Shard recovery workers", "LEAST_RUNNING", 2,
                Map.of(), true
        ));
        UUID nodeInstanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "shard-recovery-node-" + suffix, nodeInstanceId.toString(), Map.of("shell", true), Map.of(), 1,
                Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "shard_recovery_" + suffix, "Recover shard target", TaskType.IMMEDIATE, 60,
                cluster.id(), null, null, ExecutionMode.MULTI_NODE_SHARD, true, 1,
                "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run shard recovery", StepKind.NORMAL, "shard_recovery", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "shard_recovery_" + suffix, "TEST", suffix, null, 50, Map.of()
        ));
        var firstExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        jdbcTemplate.update("UPDATE task_execution SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), firstExecution.executionId().toString());

        assertEquals(1, leaseRecoveryService.recoverExpiredLeases());
        assertEquals(TaskStatus.RUNNING, service.get(task.id()).status());
        assertTrue(dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).isEmpty());
        Timestamp targetAvailableAt = jdbcTemplate.queryForObject(
                "SELECT available_at FROM task_execution_target WHERE task_instance_id = ?",
                Timestamp.class, task.id().toString());
        assertTrue(targetAvailableAt != null && targetAvailableAt.toInstant().isAfter(Instant.now()));
        jdbcTemplate.update("UPDATE task_execution_target SET available_at = ? WHERE task_instance_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), task.id().toString());
        var retryExecution = dispatchService.claim(
                new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(task.id(), retryExecution.taskInstanceId());
        assertTrue(!firstExecution.executionId().equals(retryExecution.executionId()));
    }

    @Test
    void shouldRequeueTaskAfterExecutionLeaseExpires() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "recovery_cluster_" + suffix, "Recovery workers", "LEAST_RUNNING", 5, Map.of(), true
        ));
        UUID nodeInstanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "recovery-node-" + suffix, nodeInstanceId.toString(), Map.of("shell", true), Map.of(), 1,
                Set.of(cluster.name())
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "lease_recovery_" + suffix, "Recover expired lease", TaskType.IMMEDIATE, 60, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        var step = stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run recovery probe", StepKind.NORMAL, "lease_recovery", "1.0.0", "scripts/main.sh",
                List.of(), true, 30, FailurePolicy.FAIL_TASK, 10, 1
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "recovery_" + suffix, "SYSTEM", suffix, null, 50, Map.of()
        ));
        var firstExecution = dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        jdbcTemplate.update("UPDATE task_execution SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), firstExecution.executionId().toString());

        assertEquals(1, leaseRecoveryService.recoverExpiredLeases());
        assertEquals(TaskStatus.QUEUED, service.get(task.id()).status());
        SchedulerException lateStep = assertThrows(SchedulerException.class,
                () -> dispatchService.reportStep(firstExecution.executionId(), new ReportStepExecutionRequest(
                        firstExecution.leaseToken(), step.id(), 1, TaskStatus.SUCCEEDED, 0, Map.of(), null, null)));
        assertEquals(ErrorCode.EXECUTION_LEASE_LOST, lateStep.errorCode());
        SchedulerException lateCompletion = assertThrows(SchedulerException.class,
                () -> dispatchService.complete(firstExecution.executionId(),
                        new CompleteExecutionRequest(firstExecution.leaseToken(), TaskStatus.SUCCEEDED)));
        assertEquals(ErrorCode.EXECUTION_LEASE_LOST, lateCompletion.errorCode());
        assertTrue(dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).isEmpty());
        Timestamp availableAt = jdbcTemplate.queryForObject(
                "SELECT available_at FROM task_instance WHERE id = ?", Timestamp.class, task.id().toString());
        assertTrue(availableAt != null && availableAt.toInstant().isAfter(Instant.now()));
        jdbcTemplate.update("UPDATE task_instance SET available_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), task.id().toString());
        var secondExecution = dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(task.id(), secondExecution.taskInstanceId());
        assertTrue(secondExecution.fencingToken() > firstExecution.fencingToken());
    }

    @Test
    void shouldExpirePreviouslyStartedSingleNodeTaskWhileRequeued() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "deadline_cluster_" + suffix, "Deadline workers", "LEAST_RUNNING", 1, Map.of(), true
        ));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "deadline_task_" + suffix, "Deadline task", TaskType.IMMEDIATE, 5, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 1, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        var task = service.create(new CreateTaskRequest(
                definition.name(), "deadline_" + suffix, "TEST", suffix, null, 50, Map.of()
        ));
        Instant startedAt = Instant.now().minusSeconds(10);
        jdbcTemplate.update("UPDATE task_instance SET started_at = ? WHERE id = ?",
                Timestamp.from(startedAt), task.id().toString());

        assertEquals(1, taskDeadlineService.expireDeadlines(Instant.now()));
        assertEquals(TaskStatus.TIMED_OUT, service.get(task.id()).status());
    }

    @Test
    void shouldUseMessageAssetAwareDownloadBotSnapshotPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.version,s.script_version FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "downloadbot_capture_snapshot", "capture_snapshot");

        assertEquals(3, ((Number) definition.get("version")).intValue());
        assertEquals("1.1.0", definition.get("script_version"));
    }

    @Test
    void shouldSeedIndependentMessageAttachmentReconciliation() {
        Map<String, Object> submission = jdbcTemplate.queryForMap(
                "SELECT d.version,s.script_version FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "message_download_attachment", "submit_download");
        Integer reconciliation = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_definition d JOIN task_step_definition s "
                        + "ON s.task_definition_id=d.id WHERE d.name=? AND s.script_package=?",
                Integer.class, "message_reconcile_attachment_download",
                "message_reconcile_attachment_download");

        assertEquals(3, ((Number) submission.get("version")).intValue());
        assertEquals("1.1.0", submission.get("script_version"));
        assertEquals(1, reconciliation);
    }

    @Test
    void shouldUseFrozenMessageHistoryMigrationPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.version,d.result_schema,s.script_version FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "message_migrate_history", "migrate_history");

        assertEquals(2, ((Number) definition.get("version")).intValue());
        assertEquals("1.1.0", definition.get("script_version"));
        assertTrue(definition.get("result_schema").toString().contains("sourceHighWater"));
    }

    @Test
    void shouldSeedFrozenOutboundHistoryMigrationPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.result_schema,s.script_package,s.script_version FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "message_migrate_outbound_history", "migrate_outbound_history");

        assertEquals("message_migrate_outbound_history", definition.get("script_package"));
        assertEquals("1.0.0", definition.get("script_version"));
        assertTrue(definition.get("result_schema").toString().contains("sourceDigestSha256"));
    }

    @Test
    void shouldUseFrozenLegacyMediaMigrationPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.parameter_schema,d.result_schema,s.script_version FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "media_migrate_legacy_items", "migrate_legacy_media");

        assertTrue(definition.get("parameter_schema").toString().contains("migrationKey"));
        assertTrue(definition.get("result_schema").toString().contains("targetVerified"));
        assertEquals("1.1.0", definition.get("script_version"));
    }

    @Test
    void shouldUseFrozenIdentityUserMigrationPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.version,d.timeout_seconds,d.parameter_schema,d.result_schema,s.script_version "
                        + "FROM task_definition d JOIN task_step_definition s "
                        + "ON s.task_definition_id=d.id WHERE d.name=? AND s.name=?",
                "identity_migrate_users", "migrate_users");

        assertEquals(2, ((Number) definition.get("version")).intValue());
        assertEquals(1800, ((Number) definition.get("timeout_seconds")).intValue());
        assertEquals("1.1.0", definition.get("script_version"));
        assertTrue(definition.get("parameter_schema").toString().contains("migrationKey"));
        assertTrue(definition.get("result_schema").toString().contains("sourceHighWater"));
    }

    @Test
    void shouldUseFrozenReaderStateMigrationPackage() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.version,d.parameter_schema,d.result_schema,s.script_version "
                        + "FROM task_definition d JOIN task_step_definition s "
                        + "ON s.task_definition_id=d.id WHERE d.name=? AND s.name=?",
                "reader_migrate_legacy_state", "migrate_reader_state");

        assertEquals(2, ((Number) definition.get("version")).intValue());
        assertEquals("1.1.0", definition.get("script_version"));
        assertTrue(definition.get("parameter_schema").toString().contains("sourceHighWater"));
        assertTrue(definition.get("result_schema").toString().contains("digestSha256"));
    }

    @Test
    void shouldRequireReadableOwnerForLegacyAssetSnapshot() {
        String schema = jdbcTemplate.queryForObject(
                "SELECT parameter_schema FROM task_definition WHERE name=?",
                String.class, "legacy_asset_capture_snapshot");

        assertTrue(schema.contains("\"required\":[\"snapshotId\",\"ownerId\"]"));
        assertTrue(schema.contains("\"ownerId\":{\"type\":\"integer\",\"minimum\":1}"));
    }

    @Test
    void shouldSeedTwoPassLegacyMediaMigration() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.parameter_schema,d.result_schema,s.script_package,s.script_version "
                        + "FROM task_definition d JOIN task_step_definition s "
                        + "ON s.task_definition_id=d.id WHERE d.name=? AND s.name=?",
                "media_migrate_legacy_items", "migrate_legacy_media");

        assertTrue(definition.get("parameter_schema").toString().contains("sourceSnapshotId"));
        assertTrue(definition.get("result_schema").toString().contains("digestSha256"));
        assertTrue(definition.get("result_schema").toString().contains("legacyTags"));
        assertEquals("media_migrate_legacy_items", definition.get("script_package"));
        assertEquals("1.1.0", definition.get("script_version"));
    }

    @Test
    void shouldReconcileDeduplicatedMediaSources() {
        String schema = jdbcTemplate.queryForObject(
                "SELECT result_schema FROM task_definition WHERE name=?",
                String.class, "media_reconcile_library");

        assertTrue(schema.contains("sourceRelationCount"));
        assertTrue(schema.contains("sourceTagRelationCount"));
    }

    @Test
    void shouldSeedLegacyAppCatalogMigration() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.parameter_schema,d.result_schema,s.script_package FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "app_catalog_migrate_legacy", "migrate_app_catalog");

        assertTrue(definition.get("parameter_schema").toString().contains("migrationKey"));
        assertTrue(definition.get("result_schema").toString().contains("sourceHighWater"));
        assertEquals("app_catalog_migrate_legacy", definition.get("script_package"));
    }

    @Test
    void shouldSeedLegacyFeedbackMigration() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.result_schema,s.script_package FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "feedback_migrate_legacy", "migrate_feedback");

        assertTrue(definition.get("result_schema").toString().contains("sourceHighWater"));
        assertEquals("feedback_migrate_legacy", definition.get("script_package"));
    }

    @Test
    void shouldSeedLegacyDshSessionMigration() {
        Map<String, Object> definition = jdbcTemplate.queryForMap(
                "SELECT d.result_schema,s.script_package FROM task_definition d "
                        + "JOIN task_step_definition s ON s.task_definition_id=d.id "
                        + "WHERE d.name=? AND s.name=?",
                "dsh_migrate_legacy_sessions", "migrate_dsh_sessions");

        assertTrue(definition.get("result_schema").toString().contains("sourceHighWater"));
        assertEquals("dsh_migrate_legacy_sessions", definition.get("script_package"));
    }
}
