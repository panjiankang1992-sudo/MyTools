package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private JdbcTemplate jdbcTemplate;

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
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "media-node-" + suffix, nodeInstanceId.toString(), Map.of("python", "3.12"),
                Map.of("gpu", true), 4, Set.of(cluster.name())
        ));
        assertEquals(node.id(), topologyService.heartbeat(node.id(), nodeInstanceId.toString(), 1).id());

        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "media_probe_" + suffix, "Probe media", TaskType.IMMEDIATE, 120, cluster.id(), null, null,
                ExecutionMode.SINGLE_NODE, true, 10, "SKIP", "IGNORE", Map.of(), Map.of()
        ));
        var step = stepService.create(definition.id(), new CreateTaskStepRequest(
                "probe", "Run ffprobe", StepKind.NORMAL, "media_probe", "1.0.0", "scripts/main.py",
                List.of("--asset-id", "${parameters.assetId}"), true, 90, FailurePolicy.FAIL_TASK, 10, 2
        ));

        var task = service.create(new CreateTaskRequest(
                definition.name(), "probe_" + suffix, "MEDIA_ASSET", "asset-1", null, 50, Map.of("assetId", "asset-1")
        ));
        var claimed = dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(task.id(), claimed.taskInstanceId());
        assertEquals(1, claimed.steps().size());
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
        assertEquals(TaskStatus.CANCELLED, scriptApiService.cancelChild(
                claimed.executionId(), claimed.leaseToken(), child.id()).status());
        assertTrue(!dispatchService.heartbeat(claimed.executionId(),
                new LeaseHeartbeatRequest(claimed.leaseToken(), 60)).cancelRequested());
        dispatchService.reportStep(claimed.executionId(), new ReportStepExecutionRequest(
                claimed.leaseToken(), step.id(), 1, TaskStatus.SUCCEEDED, 0, Map.of("duration", 12), null, null
        ));
        dispatchService.complete(claimed.executionId(),
                new CompleteExecutionRequest(claimed.leaseToken(), TaskStatus.SUCCEEDED));

        assertEquals(1, stepService.list(definition.id()).size());
        assertEquals(TaskStatus.SUCCEEDED, service.get(task.id()).status());
        var executionResult = resultQueryService.get(task.id());
        assertEquals(TaskStatus.SUCCEEDED, executionResult.status());
        assertEquals(Map.of("duration", 12), executionResult.steps().getFirst().result());
        assertTrue(topologyService.listClusters().stream().anyMatch(item -> item.id().equals(cluster.id())));
        assertTrue(topologyService.listNodes().stream().anyMatch(item -> item.id().equals(node.id())));
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
        assertThrows(IllegalStateException.class, () -> service.create(new CreateTaskRequest(
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
        stepService.create(definition.id(), new CreateTaskStepRequest(
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
        var secondExecution = dispatchService.claim(new ClaimTaskRequest(node.id(), nodeInstanceId, 60)).orElseThrow();
        assertEquals(task.id(), secondExecution.taskInstanceId());
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
}
