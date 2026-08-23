package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.ClaimedStepView;
import com.yuyutian.mytools.task.scheduler.model.ClaimedTaskView;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatView;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import com.yuyutian.mytools.task.scheduler.repository.TaskInstanceRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskStepRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务分发、租约和执行结果服务。
 */
@Service
public class TaskDispatchService {

    private final JdbcTemplate jdbcTemplate;
    private final TaskInstanceRepository instanceRepository;
    private final TaskStepRepository stepRepository;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建任务分发服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param instanceRepository 任务实例仓储
     * @param stepRepository 任务步骤仓储
     * @param jsonColumnMapper JSON 转换器
     */
    public TaskDispatchService(JdbcTemplate jdbcTemplate, TaskInstanceRepository instanceRepository,
                               TaskStepRepository stepRepository, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.instanceRepository = instanceRepository;
        this.stepRepository = stepRepository;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 为执行节点领取一个符合集群约束的任务。
     *
     * @param request 领取请求
     * @return 可选已领取任务
     */
    @Transactional
    public Optional<ClaimedTaskView> claim(ClaimTaskRequest request) {
        validateNodeInstance(request.nodeId(), request.instanceId());
        List<UUID> candidates = jdbcTemplate.query("""
                SELECT ti.id
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                JOIN execution_cluster ec ON ec.id = td.cluster_id AND ec.enabled = TRUE
                JOIN cluster_node cn ON cn.cluster_id = ec.id AND cn.enabled = TRUE
                WHERE ti.status = 'QUEUED' AND cn.node_id = ?
                  AND EXISTS (
                      SELECT 1 FROM task_step_definition ts
                      WHERE ts.task_definition_id = td.id AND ts.enabled = TRUE AND ts.step_kind = 'NORMAL'
                  )
                ORDER BY ti.priority DESC, ti.created_at
                LIMIT 10
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString(1)), request.nodeId().toString());
        for (UUID taskId : candidates) {
            int claimed = jdbcTemplate.update("""
                    UPDATE task_instance SET status = 'RUNNING', updated_at = ?
                    WHERE id = ? AND status = 'QUEUED'
                    """, Timestamp.from(Instant.now()), taskId.toString());
            if (claimed == 1) {
                return Optional.of(createExecution(request, taskId));
            }
        }
        return Optional.empty();
    }

    /**
     * 续期执行租约并返回取消状态。
     *
     * @param executionId 执行标识
     * @param request 续期请求
     * @return 租约状态
     */
    @Transactional
    public LeaseHeartbeatView heartbeat(UUID executionId, LeaseHeartbeatRequest request) {
        Instant leaseUntil = Instant.now().plusSeconds(request.leaseSeconds());
        int updated = jdbcTemplate.update("""
                UPDATE task_execution SET lease_until = ?, updated_at = ?
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                """, Timestamp.from(leaseUntil), Timestamp.from(Instant.now()), executionId.toString(),
                request.leaseToken().toString());
        if (updated != 1) {
            throw new IllegalArgumentException("Active execution lease does not exist");
        }
        String taskStatus = jdbcTemplate.queryForObject("""
                SELECT ti.status FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                WHERE te.id = ?
                """, String.class, executionId.toString());
        return new LeaseHeartbeatView(leaseUntil, TaskStatus.CANCELLING.name().equals(taskStatus));
    }

    /**
     * 上报一个脚本步骤的最终结果。
     *
     * @param executionId 执行标识
     * @param request 结果请求
     */
    @Transactional
    public void reportStep(UUID executionId, ReportStepExecutionRequest request) {
        requireLease(executionId, request.leaseToken());
        if (request.status() != TaskStatus.SUCCEEDED && request.status() != TaskStatus.FAILED
                && request.status() != TaskStatus.CANCELLED && request.status() != TaskStatus.TIMED_OUT) {
            throw new IllegalArgumentException("Step result status is not terminal");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO step_execution (
                    id, task_execution_id, step_definition_id, attempt, status, exit_code, result_json,
                    error_code, error_message, started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), executionId.toString(), request.stepDefinitionId().toString(),
                request.attempt(), request.status().name(), request.exitCode(), jsonColumnMapper.write(request.result()),
                request.errorCode(), truncate(request.errorMessage(), 2048), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * 完成任务执行并同步任务实例最终状态。
     *
     * @param executionId 执行标识
     * @param request 完成请求
     */
    @Transactional
    public void complete(UUID executionId, CompleteExecutionRequest request) {
        requireLease(executionId, request.leaseToken());
        if (request.status() != TaskStatus.SUCCEEDED && request.status() != TaskStatus.FAILED
                && request.status() != TaskStatus.CANCELLED && request.status() != TaskStatus.TIMED_OUT) {
            throw new IllegalArgumentException("Execution status is not terminal");
        }
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE task_execution SET status = ?, finished_at = ?, updated_at = ?
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                """, request.status().name(), Timestamp.from(now), Timestamp.from(now), executionId.toString(),
                request.leaseToken().toString());
        if (updated != 1) {
            throw new IllegalStateException("Execution state changed concurrently");
        }
        String taskInstanceId = jdbcTemplate.queryForObject(
                "SELECT task_instance_id FROM task_execution WHERE id = ?", String.class, executionId.toString());
        jdbcTemplate.update("""
                UPDATE task_instance SET status = ?, progress = ?, updated_at = ?
                WHERE id = ? AND status IN ('RUNNING', 'CANCELLING')
                """, request.status().name(), request.status() == TaskStatus.SUCCEEDED ? 100 : 0,
                Timestamp.from(now), taskInstanceId);
    }

    private ClaimedTaskView createExecution(ClaimTaskRequest request, UUID taskId) {
        var task = instanceRepository.findById(taskId).orElseThrow();
        String definitionIdText = jdbcTemplate.queryForObject(
                "SELECT task_definition_id FROM task_instance WHERE id = ?", String.class, taskId.toString());
        UUID definitionId = UUID.fromString(definitionIdText);
        List<ClaimedStepView> steps = stepRepository.list(definitionId).stream()
                .filter(step -> step.enabled())
                .map(step -> new ClaimedStepView(
                        step.id(), step.name(), step.stepKind(), step.scriptPackage(), step.scriptVersion(),
                        step.entrypoint(), step.argumentsTemplate(), step.timeoutSeconds(), step.failurePolicy(),
                        step.sequenceNumber(), step.maxAttempts()))
                .toList();
        UUID executionId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(request.leaseSeconds());
        jdbcTemplate.update("""
                INSERT INTO task_execution
                (id, task_instance_id, node_id, status, lease_token, lease_until, started_at, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?)
                """, executionId.toString(), taskId.toString(), request.nodeId().toString(), leaseToken.toString(),
                Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return new ClaimedTaskView(executionId, task.id(), task.parentTaskInstanceId(), task.taskName(), leaseToken,
                leaseUntil, task.parameters(), steps);
    }

    private void validateNodeInstance(UUID nodeId, UUID instanceId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM executor_node
                WHERE id = ? AND instance_id = ? AND enabled = TRUE AND status IN ('ONLINE', 'BUSY')
                """, Integer.class, nodeId.toString(), instanceId.toString());
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Active executor node instance does not exist");
        }
    }

    private void requireLease(UUID executionId, UUID leaseToken) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_execution
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING' AND lease_until >= ?
                """, Integer.class, executionId.toString(), leaseToken.toString(), Timestamp.from(Instant.now()));
        if (count == null || count != 1) {
            throw new IllegalArgumentException("Active execution lease does not exist");
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
