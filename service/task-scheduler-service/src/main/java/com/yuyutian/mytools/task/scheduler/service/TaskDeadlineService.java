package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.config.DispatchDeadlineProperties;
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
    private final TaskEventService taskEventService;
    private final ChildTaskAggregationService childTaskAggregationService;
    private final DispatchDeadlineProperties dispatchDeadlineProperties;
    private final TaskExecutionAuthorizationService executionAuthorizationService;

    /**
     * 创建任务总超时服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param multiNodeTaskAggregationService 多节点聚合服务
     * @param taskEventService 任务事件服务
     * @param childTaskAggregationService 父子任务聚合服务
     * @param dispatchDeadlineProperties 首次派发截止配置
     */
    public TaskDeadlineService(JdbcTemplate jdbcTemplate,
                               MultiNodeTaskAggregationService multiNodeTaskAggregationService,
                               TaskEventService taskEventService,
                               ChildTaskAggregationService childTaskAggregationService,
                               DispatchDeadlineProperties dispatchDeadlineProperties,
                               TaskExecutionAuthorizationService executionAuthorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.multiNodeTaskAggregationService = multiNodeTaskAggregationService;
        this.taskEventService = taskEventService;
        this.childTaskAggregationService = childTaskAggregationService;
        this.dispatchDeadlineProperties = dispatchDeadlineProperties;
        this.executionAuthorizationService = executionAuthorizationService;
    }

    /**
     * 周期回收超过总超时的排队任务与多节点目标。
     */
    @Scheduled(fixedDelayString = "${task.scheduler.deadline-scan-delay-ms:1000}")
    @Transactional
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
        int expired = expireUnclaimedTasks(now);
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
        for (DeadlineCandidate candidate : candidates) {
            if (candidate.startedAt().plusSeconds(candidate.timeoutSeconds()).isAfter(now)) {
                continue;
            }
            if ("SINGLE_NODE".equals(candidate.executionMode()) && "QUEUED".equals(candidate.status())) {
                int updated = jdbcTemplate.update("""
                        UPDATE task_instance SET status = 'TIMED_OUT', progress = 0, updated_at = ?
                        WHERE id = ? AND status = 'QUEUED'
                        """, Timestamp.from(now), candidate.id().toString());
                if (updated == 1) {
                    executionAuthorizationService.revokeTask(candidate.id());
                    taskEventService.appendTransition(candidate.id(), candidate.status(), "TIMED_OUT", "deadline",
                            "TASK_DEADLINE_EXCEEDED", 0, now);
                    childTaskAggregationService.aggregateParentChain(candidate.id(), now);
                }
                expired += updated;
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

    private int expireUnclaimedTasks(Instant now) {
        List<DispatchDeadlineCandidate> candidates = jdbcTemplate.query("""
                SELECT id, created_at, dispatch_deadline_at
                FROM task_instance
                WHERE status = 'QUEUED' AND started_at IS NULL
                  AND (dispatch_deadline_at IS NULL OR dispatch_deadline_at <= ?)
                ORDER BY COALESCE(dispatch_deadline_at, created_at), id
                LIMIT 1000
                """, (resultSet, rowNumber) -> new DispatchDeadlineCandidate(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("dispatch_deadline_at") == null ? null
                        : resultSet.getTimestamp("dispatch_deadline_at").toInstant()), Timestamp.from(now));
        int expired = 0;
        for (DispatchDeadlineCandidate candidate : candidates) {
            Instant deadline = candidate.deadlineAt();
            if (deadline == null) {
                // 滚动升级期间旧版创建的空值先持久化截止时间，再允许新版领取。
                deadline = candidate.createdAt().plusSeconds(dispatchDeadlineProperties.queueTimeoutSeconds());
                jdbcTemplate.update("""
                        UPDATE task_instance SET dispatch_deadline_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'QUEUED' AND started_at IS NULL
                          AND dispatch_deadline_at IS NULL
                        """, Timestamp.from(deadline), Timestamp.from(now), candidate.id().toString());
            }
            if (deadline.isAfter(now)) {
                continue;
            }
            // 与首次 claim 使用互斥的状态与时间条件，保证只有一侧能成功。
            int updated = jdbcTemplate.update("""
                    UPDATE task_instance SET status = 'TIMED_OUT', progress = 0, updated_at = ?
                    WHERE id = ? AND status = 'QUEUED' AND started_at IS NULL
                      AND dispatch_deadline_at IS NOT NULL AND dispatch_deadline_at <= ?
                    """, Timestamp.from(now), candidate.id().toString(), Timestamp.from(now));
            if (updated == 1) {
                executionAuthorizationService.revokeTask(candidate.id());
                taskEventService.appendTransition(candidate.id(), "QUEUED", "TIMED_OUT", "dispatch-deadline",
                        "TASK_DISPATCH_DEADLINE_EXCEEDED", 0, now);
                childTaskAggregationService.aggregateParentChain(candidate.id(), now);
                expired++;
            }
        }
        return expired;
    }

    private record DeadlineCandidate(UUID id, String status, Instant startedAt, long timeoutSeconds,
                                     String executionMode) {
    }

    private record DispatchDeadlineCandidate(UUID id, Instant createdAt, Instant deadlineAt) {
    }
}
