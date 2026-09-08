package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ChildAggregationPolicy;
import com.yuyutian.mytools.task.scheduler.model.ChildAggregationStrategy;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 聚合父任务与直接子任务的终态。
 */
@Service
public class ChildTaskAggregationService {

    private static final int RECONCILIATION_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final TaskInstanceRepository instanceRepository;
    private final TaskEventService taskEventService;
    private final JsonColumnMapper jsonColumnMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 创建父子任务聚合服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param instanceRepository 任务实例仓储
     * @param taskEventService 任务事件服务
     * @param jsonColumnMapper JSON 列转换器
     * @param applicationEventPublisher 应用事件发布器
     */
    public ChildTaskAggregationService(JdbcTemplate jdbcTemplate,
                                       TaskInstanceRepository instanceRepository,
                                       TaskEventService taskEventService,
                                       JsonColumnMapper jsonColumnMapper,
                                       ApplicationEventPublisher applicationEventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceRepository = instanceRepository;
        this.taskEventService = taskEventService;
        this.jsonColumnMapper = jsonColumnMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 完成单节点父任务；成功执行仍有活跃子任务时进入等待状态。
     *
     * @param taskId 任务标识
     * @param currentStatus 当前任务状态
     * @param requestedStatus 执行上报终态
     * @param sourceId 执行来源标识
     * @param now 完成时间
     */
    @Transactional
    public void completeOrWait(UUID taskId, String currentStatus, TaskStatus requestedStatus,
                               String sourceId, Instant now) {
        ChildAggregate aggregate = childAggregate(taskId);
        if (TaskStatus.CANCELLING.name().equals(currentStatus) && aggregate.active() > 0) {
            // 取消传播尚未收敛时保持 CANCELLING，禁止迟到的成功上报覆盖取消状态。
            return;
        }
        TaskStatus targetStatus = requestedStatus;
        int progress = requestedStatus == TaskStatus.SUCCEEDED ? 100 : 0;
        boolean childAggregationApplied = false;
        if (TaskStatus.CANCELLING.name().equals(currentStatus)) {
            targetStatus = TaskStatus.CANCELLED;
            progress = 0;
        } else if (requestedStatus == TaskStatus.SUCCEEDED && aggregate.total() > 0) {
            if (aggregate.active() > 0) {
                targetStatus = TaskStatus.WAITING_CHILDREN;
                progress = 99;
            } else {
                // 子任务可能在父执行上报前已终止，此时仍必须按配置的策略聚合全部子任务。
                targetStatus = policySatisfied(childAggregationPolicy(taskId), aggregate)
                        ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
                progress = targetStatus == TaskStatus.SUCCEEDED ? 100 : 0;
                childAggregationApplied = true;
            }
        }
        int updated = jdbcTemplate.update("""
                UPDATE task_instance SET status = ?, progress = ?, updated_at = ?
                WHERE id = ? AND status IN ('RUNNING', 'CANCELLING')
                """, targetStatus.name(), progress, Timestamp.from(now), taskId.toString());
        if (updated == 1) {
            String reason = transitionReason(targetStatus, childAggregationApplied);
            taskEventService.appendTransition(taskId, currentStatus, targetStatus.name(), sourceId,
                    reason, progress, now);
            if (targetStatus != TaskStatus.WAITING_CHILDREN) {
                aggregateParentChainNow(taskId, now);
            }
            // 事务提交后再检查一次，关闭最后一个子任务与父任务进入等待状态之间的竞态窗口。
            publishPostCommitAggregation(taskId);
        }
    }

    /**
     * 从已终止子任务向上聚合等待中的祖先任务。
     *
     * @param childTaskId 已终止子任务标识
     * @param now 聚合时间
     */
    @Transactional
    public void aggregateParentChain(UUID childTaskId, Instant now) {
        aggregateParentChainNow(childTaskId, now);
        // 外层事务可能刚写入子任务终态，提交后复核可避免同级子任务并发完成时的快照遗漏。
        publishPostCommitAggregation(childTaskId);
    }

    /**
     * 在触发事务提交后复核当前任务及其父链。
     *
     * @param request 聚合复核请求
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void aggregateAfterCommit(AggregationRequest request) {
        Instant now = Instant.now();
        aggregateWaitingTask(request.taskId(), now);
        aggregateParentChainNow(request.taskId(), now);
    }

    /**
     * 有界修复已满足终态条件的等待中父任务。
     *
     * @return 本批复核的父任务数量
     */
    @Scheduled(fixedDelayString = "${task.scheduler.child-aggregation-scan-delay-ms:1000}")
    @Transactional
    public int reconcileReadyParents() {
        var parentIds = instanceRepository.findReadyWaitingParentIds(RECONCILIATION_BATCH_SIZE);
        Instant now = Instant.now();
        for (UUID parentId : parentIds) {
            // 每次状态改写都带 WAITING_CHILDREN 条件，多实例并发扫描也只会成功一次。
            aggregateWaitingTask(parentId, now);
            aggregateParentChainNow(parentId, now);
        }
        return parentIds.size();
    }

    private void aggregateParentChainNow(UUID childTaskId, Instant now) {
        UUID currentChildId = childTaskId;
        for (int depth = 0; depth < TaskCancellationPropagationService.MAX_TASK_DEPTH; depth++) {
            var child = instanceRepository.findById(currentChildId).orElse(null);
            if (child == null || child.parentTaskInstanceId() == null) {
                return;
            }
            UUID parentId = child.parentTaskInstanceId();
            var parent = instanceRepository.findById(parentId).orElse(null);
            if (parent == null || parent.status() != TaskStatus.WAITING_CHILDREN) {
                return;
            }
            ChildAggregate aggregate = childAggregate(parentId);
            if (aggregate.active() > 0) {
                return;
            }
            ChildAggregationPolicy policy = childAggregationPolicy(parentId);
            TaskStatus targetStatus = policySatisfied(policy, aggregate)
                    ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
            int progress = targetStatus == TaskStatus.SUCCEEDED ? 100 : 0;
            int updated = jdbcTemplate.update("""
                    UPDATE task_instance SET status = ?, progress = ?, updated_at = ?
                    WHERE id = ? AND status = 'WAITING_CHILDREN'
                    """, targetStatus.name(), progress, Timestamp.from(now), parentId.toString());
            if (updated != 1) {
                return;
            }
            taskEventService.appendTransition(parentId, TaskStatus.WAITING_CHILDREN.name(),
                    targetStatus.name(), "child-aggregate:" + currentChildId,
                    targetStatus == TaskStatus.SUCCEEDED ? "CHILD_AGGREGATION_SATISFIED"
                            : "CHILD_AGGREGATION_NOT_SATISFIED",
                    progress, now);
            currentChildId = parentId;
        }
    }

    private void aggregateWaitingTask(UUID taskId, Instant now) {
        var task = instanceRepository.findById(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.WAITING_CHILDREN) {
            return;
        }
        ChildAggregate aggregate = childAggregate(taskId);
        if (aggregate.active() > 0) {
            return;
        }
        TaskStatus targetStatus = policySatisfied(childAggregationPolicy(taskId), aggregate)
                ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
        int progress = targetStatus == TaskStatus.SUCCEEDED ? 100 : 0;
        int updated = jdbcTemplate.update("""
                UPDATE task_instance SET status = ?, progress = ?, updated_at = ?
                WHERE id = ? AND status = 'WAITING_CHILDREN'
                """, targetStatus.name(), progress, Timestamp.from(now), taskId.toString());
        if (updated == 1) {
            taskEventService.appendTransition(taskId, TaskStatus.WAITING_CHILDREN.name(),
                    targetStatus.name(), "child-aggregate:post-commit",
                    targetStatus == TaskStatus.SUCCEEDED ? "CHILD_AGGREGATION_SATISFIED"
                            : "CHILD_AGGREGATION_NOT_SATISFIED",
                    progress, now);
        }
    }

    private ChildAggregate childAggregate(UUID parentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN status IN ('CREATED', 'QUEUED', 'RUNNING', 'WAITING_CHILDREN', 'CANCELLING')
                           THEN 1 ELSE 0 END) AS active,
                       SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded
                FROM task_instance WHERE parent_task_instance_id = ?
                """, (resultSet, rowNumber) -> new ChildAggregate(
                resultSet.getInt("total"), resultSet.getInt("active"), resultSet.getInt("succeeded")),
                parentId.toString());
    }

    private ChildAggregationPolicy childAggregationPolicy(UUID parentId) {
        String value = jdbcTemplate.queryForObject("""
                SELECT definition.child_aggregation_policy_json
                FROM task_instance instance
                JOIN task_definition definition ON definition.id = instance.task_definition_id
                WHERE instance.id = ?
                """, String.class, parentId.toString());
        if (value == null || value.isBlank()) {
            return ChildAggregationPolicy.allSuccess();
        }
        var policy = jsonColumnMapper.read(value);
        Object minimum = policy.get("minSuccessCount");
        return new ChildAggregationPolicy(
                ChildAggregationStrategy.valueOf(String.valueOf(policy.get("strategy"))),
                minimum instanceof Number number ? number.intValue() : null);
    }

    private boolean policySatisfied(ChildAggregationPolicy policy, ChildAggregate aggregate) {
        return switch (policy.strategy()) {
            case ALL_SUCCESS -> aggregate.total() > 0 && aggregate.succeeded() == aggregate.total();
            case ANY_SUCCESS -> aggregate.succeeded() > 0;
            case MIN_SUCCESS_COUNT -> policy.minSuccessCount() != null
                    && aggregate.succeeded() >= policy.minSuccessCount();
        };
    }

    private String transitionReason(TaskStatus targetStatus, boolean childAggregationApplied) {
        if (targetStatus == TaskStatus.WAITING_CHILDREN) {
            return "EXECUTION_WAITING_FOR_CHILDREN";
        }
        if (childAggregationApplied) {
            return targetStatus == TaskStatus.SUCCEEDED ? "CHILD_AGGREGATION_SATISFIED"
                    : "CHILD_AGGREGATION_NOT_SATISFIED";
        }
        return "EXECUTION_TERMINATED";
    }

    private void publishPostCommitAggregation(UUID taskId) {
        applicationEventPublisher.publishEvent(new AggregationRequest(taskId));
    }

    private record ChildAggregate(int total, int active, int succeeded) {
    }

    /**
     * 事务提交后的父子聚合复核请求。
     *
     * @param taskId 当前任务标识
     */
    public record AggregationRequest(UUID taskId) {
    }
}
