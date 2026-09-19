package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateExecutionClusterRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.NodeStatus;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import com.yuyutian.mytools.task.scheduler.model.UpdateExecutorNodeStatusRequest;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TaskDispatchDrainConcurrencyTest {

    @Autowired
    private TaskInstanceService taskInstanceService;

    @Autowired
    private TaskDefinitionService definitionService;

    @Autowired
    private TaskStepService stepService;

    @Autowired
    private ExecutionTopologyService topologyService;

    @Autowired
    private TaskDispatchService dispatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldRejectAtomicIdleDrainWhenConcurrentClaimWinsNodeLock() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var cluster = topologyService.createCluster(new CreateExecutionClusterRequest(
                "drain_race_cluster_" + suffix, "Drain race cluster", "LEAST_RUNNING", 2, Map.of(), true));
        UUID instanceId = UUID.randomUUID();
        var node = topologyService.registerNode(new RegisterExecutorNodeRequest(
                "drain-race-node-" + suffix, instanceId.toString(), Map.of(), Map.of(), 2,
                Set.of(cluster.name())));
        var definition = definitionService.create(new CreateTaskDefinitionRequest(
                "drain_race_task_" + suffix, "Drain race task", TaskType.IMMEDIATE, 120,
                cluster.id(), null, null, ExecutionMode.SINGLE_NODE, true, 2,
                "SKIP", "IGNORE", Map.of(), Map.of()));
        stepService.create(definition.id(), new CreateTaskStepRequest(
                "run", "Run", StepKind.NORMAL, "system_executor_acceptance", "1.0.0",
                "scripts/main.py", List.of(), true, 60, FailurePolicy.FAIL_TASK, 10, 1));
        var task = taskInstanceService.create(new CreateTaskRequest(
                definition.name(), "drain_race_" + suffix, "TEST", suffix, null, 50, Map.of()));
        CountDownLatch capacityLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseCapacityLock = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(3)) {
            var capacityBlocker = executor.submit(() -> {
                holdDefinitionLock(definition.id(), capacityLockAcquired, releaseCapacityLock);
                return null;
            });
            assertTrue(capacityLockAcquired.await(5, TimeUnit.SECONDS));
            var claim = executor.submit(() -> dispatchService.claim(
                    new ClaimTaskRequest(node.id(), instanceId, UUID.randomUUID(), 60)).orElseThrow());
            awaitNodeLock(node.id());
            var drain = executor.submit(() -> topologyService.updateNodeStatus(node.id(),
                    new UpdateExecutorNodeStatusRequest(
                            NodeStatus.DRAINING, "QQ_FLOW_RELEASE", instanceId.toString(), 0)));

            assertThrows(TimeoutException.class, () -> drain.get(200, TimeUnit.MILLISECONDS));
            releaseCapacityLock.countDown();
            capacityBlocker.get(5, TimeUnit.SECONDS);
            var claimed = claim.get(5, TimeUnit.SECONDS);
            ExecutionException conflict = assertThrows(
                    ExecutionException.class, () -> drain.get(5, TimeUnit.SECONDS));
            assertTrue(conflict.getCause() instanceof SchedulerException);
            assertEquals(NodeStatus.ONLINE, topologyService.listNodes().stream()
                    .filter(candidate -> candidate.id().equals(node.id())).findFirst().orElseThrow().status());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_execution WHERE node_id = ? AND status = 'RUNNING'",
                    Integer.class, node.id().toString()));

            dispatchService.complete(claimed.executionId(),
                    new CompleteExecutionRequest(claimed.leaseToken(), TaskStatus.SUCCEEDED));
            assertEquals(TaskStatus.SUCCEEDED, taskInstanceService.get(task.id()).status());
            topologyService.heartbeat(node.id(), instanceId.toString(), 0);
            assertEquals(NodeStatus.DRAINING, topologyService.updateNodeStatus(node.id(),
                    new UpdateExecutorNodeStatusRequest(
                            NodeStatus.DRAINING, "QQ_FLOW_RELEASE", instanceId.toString(), 0)).status());
        } finally {
            releaseCapacityLock.countDown();
        }
    }

    private void holdDefinitionLock(UUID definitionId, CountDownLatch acquired, CountDownLatch release)
            throws SQLException, InterruptedException {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT id FROM task_definition WHERE id = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            statement.setString(1, definitionId.toString());
            statement.executeQuery();
            acquired.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            connection.commit();
        }
    }

    private void awaitNodeLock(UUID nodeId) throws Exception {
        String jdbcUrl;
        String username;
        try (Connection connection = dataSource.getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
            username = connection.getMetaData().getUserName();
        }
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, "");
                 var timeout = connection.createStatement();
                 var statement = connection.prepareStatement(
                         "SELECT id FROM executor_node WHERE id = ? FOR UPDATE")) {
                connection.setAutoCommit(false);
                timeout.execute("SET LOCK_TIMEOUT 50");
                statement.setString(1, nodeId.toString());
                statement.executeQuery();
                connection.rollback();
            } catch (SQLException exception) {
                if (exception.getErrorCode() == 50200) {
                    return;
                }
                throw exception;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Claim did not lock the executor node");
    }
}
