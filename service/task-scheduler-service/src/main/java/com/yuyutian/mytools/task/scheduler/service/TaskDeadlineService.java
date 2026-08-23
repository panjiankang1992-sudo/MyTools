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
 * 回收超过任务总超时时间但尚未被节点执行的队列目标。
 */
@Service
public class TaskDeadlineService {

    private final JdbcTemplate jdbcTemplate;
    private final MultiNodeTaskAggregationService multiNodeTaskAggregationService;

    /**
     * 创建任务总超时服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param multiNodeTaskAggregationService 多节点聚合服务
     */
    public TaskDeadlineService(JdbcTemplate jdbcTemplate,
                               MultiNodeTaskAggregationService multiNodeTaskAggregationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
    }

    /**
     * 周期回收超过总超时的排队任务与多节点目标。
     */
    @Scheduled(fixedDelayString = "${task.scheduler.deadline-scan-delay-ms:1000}")
    public void scheduledExpire() {
        expireDeadlines(Instant.now());
    }

    /**
     * 在指定时间回收超过总超时且无法由执行节点自行结束的目标。
     *
     * @param now 当前时间
     * @return 发生状态更新的任务数量
     */
    @Transactional
    public int expireDeadlines(Instant now) {
        List<DeadlineCandidate> candidates = jdbcTemplate.query("""
                SELECT ti.id, ti.status, ti.started_at, td.timeout_seconds, td.execution_mode
                FROM task_instance ti
                JOIN task_definition td ON td.id = ti.task_definition_id
                WHERE ti.started_at IS NOT NULL AND ti.status IN ('QUEUED', 'RUNNING')
                ORDER BY ti.started_at
                LIMIT 1000
                """, (resultSet, rowNumber) -> new DeadlineCandidate(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("status"),
                resultSet.getTimestamp("started_at").toInstant(), resultSet.getLong("timeout_seconds"),
                resultSet.getString("execution_mode")
        ));
        int expired = 0;
        for (DeadlineCandidate candidate : candidates) {
            if (candidate.startedAt().plusSeconds(candidate.timeoutSeconds()).isAfter(now)) {
                continue;
            }
            if ("SINGLE_NODE".equals(candidate.executionMode()) && "QUEUED".equals(candidate.status())) {
                expired += jdbcTemplate.update("""
                        UPDATE task_instance SET status = 'TIMED_OUT', progress = 0, updated_at = ?
                        WHERE id = ? AND status = 'QUEUED'
                        """, Timestamp.from(now), candidate.id().toString());
                continue;
            }
            if (!"SINGLE_NODE".equals(candidate.executionMode())) {
                int updated = jdbcTemplate.update("""
                        UPDATE task_execution_target SET status = 'TIMED_OUT', updated_at = ?
                        WHERE task_instance_id = ? AND status = 'QUEUED'
                        """, Timestamp.from(now), candidate.id().toString());
                if (updated > 0) {
                    multiNodeTaskAggregationService.aggregate(candidate.id(), now);
                    expired++;
                }
            }
        }
        return expired;
    }

    private record DeadlineCandidate(UUID id, String status, Instant startedAt, long timeoutSeconds,
                                     String executionMode) {
    }
}
