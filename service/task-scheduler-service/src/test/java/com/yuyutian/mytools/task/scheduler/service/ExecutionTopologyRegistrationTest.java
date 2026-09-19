package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.ExecutorNodeView;
import com.yuyutian.mytools.task.scheduler.model.NodeStatus;
import com.yuyutian.mytools.task.scheduler.model.RegisterExecutorNodeRequest;
import com.yuyutian.mytools.task.scheduler.model.UpdateExecutorNodeStatusRequest;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ExecutionTopologyRegistrationTest {

    @Autowired
    private ExecutionTopologyService topologyService;

    @Autowired
    private ExecutorNodeHealthService nodeHealthService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Validator validator;

    @Test
    void shouldPreserveQqFlowReleaseDrainAcrossNewInstanceRegistration() {
        String nodeName = uniqueNodeName("release-draining-node-");
        var original = register(nodeName);
        topologyService.updateNodeStatus(original.id(),
                statusRequest(NodeStatus.DRAINING, "QQ_FLOW_RELEASE", original.instanceId()));
        Timestamp statusChangedAt = statusChangedAt(original.id());

        var registered = register(nodeName);

        assertEquals(original.id(), registered.id());
        assertEquals(NodeStatus.DRAINING, registered.status());
        assertEquals("QQ_FLOW_RELEASE", statusReason(original.id()));
        assertEquals(statusChangedAt, statusChangedAt(original.id()));
    }

    @Test
    void shouldRestoreOnlineRegistrationForOtherDrainReason() {
        String nodeName = uniqueNodeName("maintenance-draining-node-");
        var original = register(nodeName);
        topologyService.updateNodeStatus(original.id(),
                statusRequest(NodeStatus.DRAINING, "MAINTENANCE", original.instanceId()));

        var registered = register(nodeName);

        assertEquals(original.id(), registered.id());
        assertEquals(NodeStatus.ONLINE, registered.status());
        assertEquals("NODE_REGISTERED", statusReason(original.id()));
    }

    @Test
    void shouldKeepNormalRegistrationOnline() {
        String nodeName = uniqueNodeName("online-node-");
        var original = register(nodeName);

        var registered = register(nodeName);

        assertEquals(original.id(), registered.id());
        assertEquals(NodeStatus.ONLINE, registered.status());
        assertEquals("NODE_REGISTERED", statusReason(original.id()));
    }

    @Test
    void shouldPreserveStaleQqFlowReleaseDrainThroughScanAndRegistration() {
        String nodeName = uniqueNodeName("stale-release-draining-node-");
        var original = register(nodeName);
        topologyService.updateNodeStatus(original.id(),
                statusRequest(NodeStatus.DRAINING, "QQ_FLOW_RELEASE", original.instanceId()));
        Instant now = Instant.now();
        makeHeartbeatStale(original.id(), now);

        nodeHealthService.scanOfflineNodes(now);
        var registered = register(nodeName);

        assertEquals(NodeStatus.DRAINING, registered.status());
        assertEquals("QQ_FLOW_RELEASE", statusReason(original.id()));
    }

    @Test
    void shouldStillTimeoutStaleNodeWithOtherDrainReason() {
        String nodeName = uniqueNodeName("stale-maintenance-draining-node-");
        var original = register(nodeName);
        topologyService.updateNodeStatus(original.id(),
                statusRequest(NodeStatus.DRAINING, "MAINTENANCE", original.instanceId()));
        Instant now = Instant.now();
        makeHeartbeatStale(original.id(), now);

        nodeHealthService.scanOfflineNodes(now);

        assertEquals(NodeStatus.OFFLINE, findStatus(original.id()));
        assertEquals("HEARTBEAT_TIMEOUT", statusReason(original.id()));
    }

    @Test
    void shouldRejectDrainFromSupersededInstanceWithoutChangingCurrentNode() {
        String nodeName = uniqueNodeName("superseded-drain-node-");
        var original = register(nodeName);
        var replacement = register(nodeName);

        SchedulerException conflict = assertThrows(SchedulerException.class,
                () -> topologyService.updateNodeStatus(original.id(),
                        statusRequest(NodeStatus.DRAINING, "QQ_FLOW_RELEASE", original.instanceId())));

        assertEquals(ErrorCode.EXECUTION_STATE_CONFLICT, conflict.errorCode());
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        ExecutorNodeView current = findNode(original.id());
        assertEquals(replacement.instanceId(), current.instanceId());
        assertEquals(NodeStatus.ONLINE, current.status());
        assertEquals("NODE_REGISTERED", statusReason(original.id()));
    }

    @Test
    void shouldRejectOnlineFromSupersededInstanceAndKeepReplacementDraining() {
        String nodeName = uniqueNodeName("superseded-online-node-");
        var original = register(nodeName);
        topologyService.updateNodeStatus(original.id(),
                statusRequest(NodeStatus.DRAINING, "QQ_FLOW_RELEASE", original.instanceId()));
        var replacement = register(nodeName);

        SchedulerException conflict = assertThrows(SchedulerException.class,
                () -> topologyService.updateNodeStatus(original.id(),
                        statusRequest(NodeStatus.ONLINE, "QQ_FLOW_RELEASE_COMPLETE", original.instanceId())));

        assertEquals(ErrorCode.EXECUTION_STATE_CONFLICT, conflict.errorCode());
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        ExecutorNodeView current = findNode(original.id());
        assertEquals(replacement.instanceId(), current.instanceId());
        assertEquals(NodeStatus.DRAINING, current.status());
        assertEquals("QQ_FLOW_RELEASE", statusReason(original.id()));
    }

    @Test
    void shouldReturnMatchingInstanceAfterGuardedStatusUpdate() {
        var node = register(uniqueNodeName("guarded-status-node-"));

        ExecutorNodeView updated = topologyService.updateNodeStatus(node.id(),
                statusRequest(NodeStatus.DRAINING, "QQ_FLOW_RELEASE", node.instanceId()));

        assertEquals(node.instanceId(), updated.instanceId());
        assertEquals(NodeStatus.DRAINING, updated.status());
    }

    @Test
    void shouldFenceStatusPatchWhenReplacementCommitsWhilePatchWaitsForRow() throws Exception {
        var original = register(uniqueNodeName("concurrent-replacement-node-"));
        String replacementInstanceId = UUID.randomUUID().toString();

        try (Connection connection = dataSource.getConnection();
            var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            updateInstanceInTransaction(connection, original.id(), replacementInstanceId);
            var patch = executor.submit(() -> topologyService.updateNodeStatus(original.id(),
                    statusRequest(NodeStatus.ONLINE, "QQ_FLOW_RELEASE_COMPLETE", original.instanceId())));

            assertThrows(TimeoutException.class, () -> patch.get(200, TimeUnit.MILLISECONDS));
            connection.commit();
            ExecutionException conflict = assertThrows(ExecutionException.class,
                    () -> patch.get(5, TimeUnit.SECONDS));

            SchedulerException schedulerException = (SchedulerException) conflict.getCause();
            assertEquals(ErrorCode.EXECUTION_STATE_CONFLICT, schedulerException.errorCode());
            assertEquals(HttpStatus.CONFLICT, schedulerException.status());
        }

        ExecutorNodeView current = findNode(original.id());
        assertEquals(replacementInstanceId, current.instanceId());
        assertEquals(NodeStatus.ONLINE, current.status());
    }

    @Test
    void shouldRequireExpectedInstanceForDrainAndOnlineContracts() {
        for (NodeStatus status : List.of(NodeStatus.DRAINING, NodeStatus.ONLINE)) {
            var violations = validator.validate(new UpdateExecutorNodeStatusRequest(status, "release", null));

            assertEquals(Set.of("expectedInstanceId"), violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    private ExecutorNodeView register(String nodeName) {
        return topologyService.registerNode(new RegisterExecutorNodeRequest(
                nodeName, UUID.randomUUID().toString(), Map.of(), Map.of(), 1, Set.of()));
    }

    private UpdateExecutorNodeStatusRequest statusRequest(NodeStatus status, String reason, String instanceId) {
        return new UpdateExecutorNodeStatusRequest(status, reason, instanceId);
    }

    private void updateInstanceInTransaction(Connection connection, UUID nodeId, String instanceId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE executor_node
                SET instance_id = ?, status = 'ONLINE', status_reason = 'NODE_REGISTERED'
                WHERE id = ?
                """)) {
            statement.setString(1, instanceId);
            statement.setString(2, nodeId.toString());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private String uniqueNodeName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String statusReason(UUID nodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status_reason FROM executor_node WHERE id = ?", String.class, nodeId.toString());
    }

    private Timestamp statusChangedAt(UUID nodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status_changed_at FROM executor_node WHERE id = ?", Timestamp.class, nodeId.toString());
    }

    private void makeHeartbeatStale(UUID nodeId, Instant now) {
        jdbcTemplate.update("UPDATE executor_node SET last_heartbeat_at = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(61)), nodeId.toString());
    }

    private NodeStatus findStatus(UUID nodeId) {
        return findNode(nodeId).status();
    }

    private ExecutorNodeView findNode(UUID nodeId) {
        return topologyService.listNodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }
}
