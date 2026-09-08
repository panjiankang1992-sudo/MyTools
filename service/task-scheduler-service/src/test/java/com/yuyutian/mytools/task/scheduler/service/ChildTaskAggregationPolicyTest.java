package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ChildAggregationPolicy;
import com.yuyutian.mytools.task.scheduler.model.ChildAggregationStrategy;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChildTaskAggregationPolicyTest {

    @Autowired
    private TaskDefinitionService definitionService;

    @Autowired
    private TaskInstanceService instanceService;

    @Autowired
    private ChildTaskAggregationService aggregationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyFailedChildWhenChildTerminatesBeforeParentReportsSuccess() {
        var parent = createTask("early_failed_parent_", ChildAggregationPolicy.allSuccess(), null);
        var failed = createTask("early_failed_child_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.RUNNING);
        setStatus(failed.id(), TaskStatus.FAILED);

        // 子任务终止时父任务尚在运行，当次向上聚合不会改写父状态。
        aggregationService.aggregateParentChain(failed.id(), Instant.now());
        assertEquals(TaskStatus.RUNNING, instanceService.get(parent.id()).status());

        aggregationService.completeOrWait(parent.id(), TaskStatus.RUNNING.name(),
                TaskStatus.SUCCEEDED, "parent-execution", Instant.now());

        assertEquals(TaskStatus.FAILED, instanceService.get(parent.id()).status());
        assertEquals(1, terminalAggregationEventCount(parent.id()));
    }

    @Test
    void shouldApplyPolicyToTerminalChildrenWhenParentReportsSuccess() {
        var parent = createTask("early_any_parent_",
                policy(ChildAggregationStrategy.ANY_SUCCESS, null), null);
        var succeeded = createTask("early_any_success_", ChildAggregationPolicy.allSuccess(), parent.id());
        var failed = createTask("early_any_failure_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.RUNNING);
        setStatus(succeeded.id(), TaskStatus.SUCCEEDED);
        setStatus(failed.id(), TaskStatus.FAILED);

        aggregationService.completeOrWait(parent.id(), TaskStatus.RUNNING.name(),
                TaskStatus.SUCCEEDED, "parent-execution", Instant.now());

        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(parent.id()).status());
        assertEquals(1, terminalAggregationEventCount(parent.id()));
    }

    @Test
    void shouldReconcileWaitingParentAfterACompletionNotificationWasMissed() {
        var parent = createTask("reconcile_parent_", ChildAggregationPolicy.allSuccess(), null);
        var failed = createTask("reconcile_child_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(failed.id(), TaskStatus.FAILED);

        aggregationService.reconcileReadyParents();

        assertEquals(TaskStatus.FAILED, instanceService.get(parent.id()).status());
        assertEquals(1, terminalAggregationEventCount(parent.id()));
    }

    @Test
    void shouldAggregateConcurrentTerminalChildrenWithoutLosingTheLastCompletion() throws Exception {
        var parent = createTask("concurrent_parent_", ChildAggregationPolicy.allSuccess(), null);
        var succeeded = createTask("concurrent_success_", ChildAggregationPolicy.allSuccess(), parent.id());
        var failed = createTask("concurrent_failure_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(succeeded.id(), TaskStatus.RUNNING);
        setStatus(failed.id(), TaskStatus.RUNNING);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var successCompletion = executor.submit(() -> {
                start.await();
                aggregationService.completeOrWait(succeeded.id(), TaskStatus.RUNNING.name(),
                        TaskStatus.SUCCEEDED, "success-execution", Instant.now());
                return null;
            });
            var failedCompletion = executor.submit(() -> {
                start.await();
                aggregationService.completeOrWait(failed.id(), TaskStatus.RUNNING.name(),
                        TaskStatus.FAILED, "failed-execution", Instant.now());
                return null;
            });
            start.countDown();
            successCompletion.get();
            failedCompletion.get();
        }

        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(succeeded.id()).status());
        assertEquals(TaskStatus.FAILED, instanceService.get(failed.id()).status());
        assertEquals(TaskStatus.FAILED, instanceService.get(parent.id()).status());
        assertEquals(1, terminalAggregationEventCount(parent.id()));
    }

    @Test
    void shouldSucceedWhenAnyChildSucceedsAndRemainIdempotent() {
        var parent = createTask("any_parent_", policy(ChildAggregationStrategy.ANY_SUCCESS, null), null);
        var succeeded = createTask("any_child_success_", ChildAggregationPolicy.allSuccess(), parent.id());
        var failed = createTask("any_child_failed_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(succeeded.id(), TaskStatus.SUCCEEDED);
        setStatus(failed.id(), TaskStatus.FAILED);

        aggregationService.aggregateParentChain(succeeded.id(), Instant.now());
        aggregationService.aggregateParentChain(succeeded.id(), Instant.now());

        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(parent.id()).status());
        assertEquals(1, terminalAggregationEventCount(parent.id()));
    }

    @Test
    void shouldApplyMinimumSuccessThreshold() {
        var parent = createTask("threshold_parent_",
                policy(ChildAggregationStrategy.MIN_SUCCESS_COUNT, 2), null);
        var first = createTask("threshold_child_first_", ChildAggregationPolicy.allSuccess(), parent.id());
        var second = createTask("threshold_child_second_", ChildAggregationPolicy.allSuccess(), parent.id());
        var failed = createTask("threshold_child_failed_", ChildAggregationPolicy.allSuccess(), parent.id());
        setStatus(parent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(first.id(), TaskStatus.SUCCEEDED);
        setStatus(second.id(), TaskStatus.SUCCEEDED);
        setStatus(failed.id(), TaskStatus.FAILED);

        aggregationService.aggregateParentChain(failed.id(), Instant.now());

        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(parent.id()).status());

        var unsatisfiedParent = createTask("threshold_unsatisfied_parent_",
                policy(ChildAggregationStrategy.MIN_SUCCESS_COUNT, 2), null);
        var onlySuccess = createTask("threshold_only_success_", ChildAggregationPolicy.allSuccess(),
                unsatisfiedParent.id());
        var secondFailure = createTask("threshold_second_failure_", ChildAggregationPolicy.allSuccess(),
                unsatisfiedParent.id());
        setStatus(unsatisfiedParent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(onlySuccess.id(), TaskStatus.SUCCEEDED);
        setStatus(secondFailure.id(), TaskStatus.FAILED);
        aggregationService.aggregateParentChain(secondFailure.id(), Instant.now());
        assertEquals(TaskStatus.FAILED, instanceService.get(unsatisfiedParent.id()).status());
    }

    @Test
    void shouldAggregateConfiguredPolicyAcrossMultipleLevelsAndRejectInvalidPolicy() {
        var grandParent = createTask("chain_grand_parent_",
                policy(ChildAggregationStrategy.ANY_SUCCESS, null), null);
        var middle = createTask("chain_middle_", ChildAggregationPolicy.allSuccess(), grandParent.id());
        var failedSibling = createTask("chain_failed_sibling_", ChildAggregationPolicy.allSuccess(),
                grandParent.id());
        var leaf = createTask("chain_leaf_", ChildAggregationPolicy.allSuccess(), middle.id());
        setStatus(grandParent.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(middle.id(), TaskStatus.WAITING_CHILDREN);
        setStatus(failedSibling.id(), TaskStatus.FAILED);
        setStatus(leaf.id(), TaskStatus.SUCCEEDED);

        aggregationService.aggregateParentChain(leaf.id(), Instant.now());

        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(middle.id()).status());
        assertEquals(TaskStatus.SUCCEEDED, instanceService.get(grandParent.id()).status());
        assertThrows(IllegalArgumentException.class, () -> createDefinition("invalid_policy_",
                policy(ChildAggregationStrategy.MIN_SUCCESS_COUNT, 0)));
    }

    private com.yuyutian.mytools.task.scheduler.model.TaskInstanceView createTask(
            String prefix, ChildAggregationPolicy policy, UUID parentId) {
        var definition = createDefinition(prefix, policy);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return instanceService.create(new CreateTaskRequest(definition.name(), "aggregation_" + suffix,
                "AGGREGATION_TEST", suffix, parentId, 50, Map.of()));
    }

    private com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView createDefinition(
            String prefix, ChildAggregationPolicy policy) {
        return definitionService.create(new CreateTaskDefinitionRequest(
                prefix + UUID.randomUUID().toString().replace("-", ""), "Aggregation test",
                TaskType.IMMEDIATE, 60, null, null, null, ExecutionMode.SINGLE_NODE, true, 1,
                "SKIP", "IGNORE", Map.of(), Map.of(), policy));
    }

    private ChildAggregationPolicy policy(ChildAggregationStrategy strategy, Integer minimum) {
        return new ChildAggregationPolicy(strategy, minimum);
    }

    private void setStatus(UUID taskId, TaskStatus status) {
        jdbcTemplate.update("UPDATE task_instance SET status = ? WHERE id = ?", status.name(), taskId.toString());
    }

    private int terminalAggregationEventCount(UUID taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_event
                WHERE task_instance_id = ?
                  AND reason IN ('CHILD_AGGREGATION_SATISFIED', 'CHILD_AGGREGATION_NOT_SATISFIED')
                """, Integer.class, taskId.toString());
        return count == null ? 0 : count;
    }
}
