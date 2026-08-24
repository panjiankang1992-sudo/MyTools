package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskExecutionResultView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 为运行中脚本提供的租约作用域任务 API。
 */
@Service
public class TaskScriptApiService {

    private final JdbcTemplate jdbcTemplate;
    private final TaskInstanceService taskInstanceService;
    private final TaskResultQueryService taskResultQueryService;

    /**
     * 创建脚本任务 API 服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param taskInstanceService 任务实例服务
     * @param taskResultQueryService 任务结果查询服务
     */
    public TaskScriptApiService(JdbcTemplate jdbcTemplate, TaskInstanceService taskInstanceService,
                                TaskResultQueryService taskResultQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskInstanceService = taskInstanceService;
        this.taskResultQueryService = taskResultQueryService;
    }

    /**
     * 在当前任务下幂等创建直接子任务。
     *
     * @param executionId 当前执行标识
     * @param request 创建请求
     * @return 子任务实例
     */
    @Transactional
    public TaskInstanceView createChild(UUID executionId, CreateChildTaskRequest request) {
        UUID currentTaskId = requireActiveLease(executionId, request.leaseToken());
        int childCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE parent_task_instance_id = ?",
                Integer.class, currentTaskId.toString());
        if (childCount >= 1000) {
            throw new IllegalStateException("Direct child task limit was reached");
        }
        return taskInstanceService.create(new CreateTaskRequest(
                request.taskName(), request.idempotencyKey(), request.businessType(), request.businessId(),
                currentTaskId, request.priority(), request.parameters(), request.requiredNodeLabels()));
    }

    /**
     * 查询当前任务或其直接子任务状态。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param targetTaskId 目标任务标识
     * @return 目标任务
     */
    public TaskInstanceView getRelated(UUID executionId, UUID leaseToken, UUID targetTaskId) {
        UUID currentTaskId = requireActiveLease(executionId, leaseToken);
        assertSelfOrDirectChild(currentTaskId, targetTaskId);
        return taskInstanceService.get(targetTaskId);
    }

    /**
     * 查询当前任务或直接子任务的步骤结果。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param targetTaskId 目标任务标识
     * @return 目标任务的步骤结果
     */
    public TaskExecutionResultView getRelatedResults(UUID executionId, UUID leaseToken, UUID targetTaskId) {
        UUID currentTaskId = requireActiveLease(executionId, leaseToken);
        assertSelfOrDirectChild(currentTaskId, targetTaskId);
        return taskResultQueryService.get(targetTaskId);
    }

    /**
     * 取消当前任务的直接子任务。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param targetTaskId 目标子任务标识
     * @return 子任务状态
     */
    @Transactional
    public TaskInstanceView cancelChild(UUID executionId, UUID leaseToken, UUID targetTaskId) {
        UUID currentTaskId = requireActiveLease(executionId, leaseToken);
        if (currentTaskId.equals(targetTaskId)) {
            throw new IllegalArgumentException("Running script cannot cancel itself through child API");
        }
        assertSelfOrDirectChild(currentTaskId, targetTaskId);
        return taskInstanceService.cancel(targetTaskId);
    }

    private UUID requireActiveLease(UUID executionId, UUID leaseToken) {
        return jdbcTemplate.query("""
                SELECT execution.task_instance_id FROM task_execution execution
                JOIN task_instance task ON task.id = execution.task_instance_id
                WHERE execution.id = ? AND execution.lease_token = ?
                  AND execution.status = 'RUNNING' AND execution.lease_until >= ?
                  AND task.status IN ('RUNNING','WAITING_CHILDREN')
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString(1)),
                executionId.toString(), leaseToken.toString(), Timestamp.from(Instant.now()))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active execution lease does not exist"));
    }

    private void assertSelfOrDirectChild(UUID currentTaskId, UUID targetTaskId) {
        if (currentTaskId.equals(targetTaskId)) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_instance
                WHERE id = ? AND parent_task_instance_id = ?
                """, Integer.class, targetTaskId.toString(), currentTaskId.toString());
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Target task is outside execution scope");
        }
    }
}
