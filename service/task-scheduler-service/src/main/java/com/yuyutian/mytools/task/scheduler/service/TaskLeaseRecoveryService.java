package com.yuyutian.mytools.task.scheduler.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 过期执行租约回收服务。
 */
@Service
public class TaskLeaseRecoveryService {

    private final JdbcTemplate jdbcTemplate;
    private final MultiNodeTaskAggregationService multiNodeTaskAggregationService;

    /**
     * 创建过期执行租约回收服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param multiNodeTaskAggregationService 多节点聚合服务
     */
    public TaskLeaseRecoveryService(JdbcTemplate jdbcTemplate,
                                    MultiNodeTaskAggregationService multiNodeTaskAggregationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
    }

    /**
     * 定时回收过期执行租约。
     */
    @Scheduled(fixedDelayString = "${task.scheduler.lease-recovery-delay-ms:10000}")
    public void scheduledRecover() {
        recoverExpiredLeases();
    }

    /**
     * 回收一批过期执行租约，并根据分发次数重新排队或结束任务。
     *
     * @return 成功回收的执行数量
     */
    @Transactional
    public int recoverExpiredLeases() {
        Instant now = Instant.now();
        List<UUID> executionIds = jdbcTemplate.query("""
                SELECT id FROM task_execution
                WHERE status = 'RUNNING' AND lease_until < ?
                ORDER BY lease_until
                LIMIT 100
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString(1)), Timestamp.from(now));
        int recovered = 0;
        for (UUID executionId : executionIds) {
            // 条件更新避免与恰好到达的续租请求争用同一个执行。
            int updated = jdbcTemplate.update("""
                    UPDATE task_execution
                    SET status = 'TIMED_OUT', finished_at = ?, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING' AND lease_until < ?
                    """, Timestamp.from(now), Timestamp.from(now), executionId.toString(), Timestamp.from(now));
            if (updated == 1) {
                recoverTaskInstance(executionId, now);
                recovered++;
            }
        }
        return recovered;
    }

    private void recoverTaskInstance(UUID executionId, Instant now) {
        RecoveryCandidate candidate = jdbcTemplate.queryForObject("""
                SELECT ti.id, ti.status, ti.dispatch_attempts, ti.max_dispatch_attempts,
                       te.execution_target_id, et.dispatch_attempts AS target_dispatch_attempts,
                       et.max_dispatch_attempts AS target_max_dispatch_attempts
                FROM task_execution te
                JOIN task_instance ti ON ti.id = te.task_instance_id
                LEFT JOIN task_execution_target et ON et.id = te.execution_target_id
                WHERE te.id = ?
                """, (resultSet, rowNumber) -> new RecoveryCandidate(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("status"),
                        resultSet.getInt("dispatch_attempts"),
                        resultSet.getInt("max_dispatch_attempts"),
                        resultSet.getString("execution_target_id") == null ? null
                                : UUID.fromString(resultSet.getString("execution_target_id")),
                        resultSet.getInt("target_dispatch_attempts"),
                        resultSet.getInt("target_max_dispatch_attempts")), executionId.toString());
        if (candidate == null) {
            return;
        }
        if (candidate.targetId() != null) {
            recoverExecutionTarget(candidate, now);
            return;
        }
        String targetStatus;
        if ("CANCELLING".equals(candidate.status())) {
            targetStatus = "CANCELLED";
        } else if (candidate.dispatchAttempts() < candidate.maxDispatchAttempts()) {
            targetStatus = "QUEUED";
        } else {
            targetStatus = "TIMED_OUT";
        }
        jdbcTemplate.update("""
                UPDATE task_instance SET status = ?, progress = 0, updated_at = ?
                WHERE id = ? AND status IN ('RUNNING', 'CANCELLING')
                """, targetStatus, Timestamp.from(now), candidate.taskInstanceId().toString());
    }

    private void recoverExecutionTarget(RecoveryCandidate candidate, Instant now) {
        String targetStatus;
        if ("CANCELLING".equals(candidate.status())) {
            targetStatus = "CANCELLED";
        } else if (candidate.targetDispatchAttempts() < candidate.targetMaxDispatchAttempts()) {
            targetStatus = "QUEUED";
        } else {
            targetStatus = "TIMED_OUT";
        }
        jdbcTemplate.update("""
                UPDATE task_execution_target SET status = ?, updated_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, targetStatus, Timestamp.from(now), candidate.targetId().toString());
        multiNodeTaskAggregationService.aggregate(candidate.taskInstanceId(), now);
    }

    private record RecoveryCandidate(UUID taskInstanceId, String status, int dispatchAttempts,
                                     int maxDispatchAttempts, UUID targetId, int targetDispatchAttempts,
                                     int targetMaxDispatchAttempts) {
    }
}
