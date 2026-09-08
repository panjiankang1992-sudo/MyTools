package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 任务状态事件与 Outbox 原子写入服务。
 */
@Service
public class TaskEventService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建任务事件服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     */
    public TaskEventService(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 幂等追加任务终态事件和对应 Outbox。
     *
     * @param taskId 任务标识
     * @param status 终态
     * @param sourceId 状态转换来源标识
     * @param now 发生时间
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendTerminal(UUID taskId, String status, String sourceId, Instant now) {
        appendTransition(taskId, null, status, sourceId, "EXECUTION_TERMINATED", null, now);
    }

    /**
     * 幂等追加任务状态转换事件和对应 Outbox。
     *
     * @param taskId 任务标识
     * @param oldStatus 原状态，创建事件可为空
     * @param newStatus 新状态
     * @param sourceId 状态转换来源标识
     * @param reason 转换原因代码
     * @param progress 可选进度
     * @param now 发生时间
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendTransition(UUID taskId, String oldStatus, String newStatus, String sourceId,
                                 String reason, Integer progress, Instant now) {
        String eventType = eventType(newStatus);
        appendEvent(taskId, eventType, oldStatus, newStatus, sourceId, reason, progress, now);
    }

    /**
     * 幂等追加任务进度事件和对应 Outbox。
     *
     * @param taskId 任务标识
     * @param status 当前状态
     * @param progress 当前进度
     * @param sourceId 进度来源标识
     * @param now 发生时间
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendProgress(UUID taskId, String status, int progress, String sourceId, Instant now) {
        appendEvent(taskId, "TaskProgressChanged", status, status, sourceId,
                "TARGET_PROGRESS_CHANGED", progress, now);
    }

    /**
     * 幂等追加需要人工处理的补偿失败事件及对应 Outbox。
     *
     * @param taskId 任务标识
     * @param status 原始任务终态
     * @param sourceId 执行标识
     * @param now 发生时间
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendCompensationRequired(UUID taskId, String status, String sourceId, Instant now) {
        appendEvent(taskId, "TaskCompensationRequired", status, status, sourceId,
                "COMPENSATION_FAILED", null, now);
    }

    private void appendEvent(UUID taskId, String eventType, String oldStatus, String newStatus,
                             String sourceId, String reason, Integer progress, Instant now) {
        UUID eventId = UUID.nameUUIDFromBytes(
                (taskId + ":" + eventType + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
        TaskMetadata metadata = jdbcTemplate.queryForObject("""
                SELECT task_name, task_definition_version, business_type, business_id
                FROM task_instance WHERE id = ?
                """, (resultSet, rowNumber) -> new TaskMetadata(
                resultSet.getString("task_name"), resultSet.getInt("task_definition_version"),
                resultSet.getString("business_type"), resultSet.getString("business_id")), taskId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", eventType);
        payload.put("taskInstanceId", taskId.toString());
        payload.put("taskName", metadata.taskName());
        payload.put("taskDefinitionVersion", metadata.definitionVersion());
        payload.put("businessType", metadata.businessType());
        payload.put("businessId", metadata.businessId());
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        payload.put("reason", reason);
        payload.put("sourceId", sourceId);
        payload.put("actorType", "SYSTEM");
        if (progress != null) {
            payload.put("progress", progress);
        }
        payload.put("occurredAt", now.toString());
        try {
            jdbcTemplate.update("""
                    INSERT INTO task_event
                    (id, task_instance_id, event_type, source_id, old_status, new_status, reason, actor_type,
                     payload_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'SYSTEM', ?, ?)
                    """, eventId.toString(), taskId.toString(), eventType, sourceId, oldStatus, newStatus, reason,
                    jsonColumnMapper.write(payload), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 事件时间线已存在时继续确认 Outbox，修复极端的中间状态。
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO task_outbox
                    (id, aggregate_type, aggregate_id, event_type, payload_json, status, created_at, next_attempt_at)
                    VALUES (?, 'TASK_INSTANCE', ?, ?, ?, 'PENDING', ?, ?)
                    """, eventId.toString(), taskId.toString(), eventType, jsonColumnMapper.write(payload),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 确定性事件 ID 保证重复终态转换不会产生重复事件。
        }
    }

    private String eventType(String status) {
        return switch (status) {
            case "QUEUED" -> "TaskQueued";
            case "RUNNING" -> "TaskStarted";
            case "CANCELLING" -> "TaskCancellationRequested";
            case "SUCCEEDED" -> "TaskSucceeded";
            case "FAILED" -> "TaskFailed";
            case "TIMED_OUT" -> "TaskTimedOut";
            case "CANCELLED" -> "TaskCancelled";
            default -> "TaskStatusChanged";
        };
    }

    /**
     * 领取待投递事件，并回收超时的处理中事件。
     *
     * @param limit 单批上限
     * @param now 领取时间
     * @return 已锁定事件
     */
    @Transactional
    public List<TaskOutboxEvent> claimPending(int limit, Instant now) {
        Instant staleBefore = now.minusSeconds(60);
        List<TaskOutboxEvent> events = jdbcTemplate.query("""
                SELECT id, event_type, payload_json, attempt_count
                FROM task_outbox
                WHERE ((status IN ('PENDING', 'RETRY') AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                       OR (status = 'PROCESSING' AND locked_at < ?))
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new TaskOutboxEvent(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("event_type"),
                resultSet.getString("payload_json"), resultSet.getInt("attempt_count") + 1),
                Timestamp.from(now), Timestamp.from(staleBefore), Math.max(1, limit));
        for (TaskOutboxEvent event : events) {
            // 先持久化处理权和尝试次数，再执行网络投递。
            jdbcTemplate.update("""
                    UPDATE task_outbox
                    SET status = 'PROCESSING', locked_at = ?, attempt_count = ?, last_error = NULL
                    WHERE id = ?
                    """, Timestamp.from(now), event.attemptCount(), event.id().toString());
        }
        return events;
    }

    /**
     * 标记事件已投递。
     *
     * @param eventId 事件标识
     * @param now 完成时间
     */
    public void markPublished(UUID eventId, Instant now) {
        jdbcTemplate.update("""
                UPDATE task_outbox
                SET status = 'PUBLISHED', published_at = ?, locked_at = NULL, next_attempt_at = NULL
                WHERE id = ? AND status = 'PROCESSING'
                """, Timestamp.from(now), eventId.toString());
    }

    /**
     * 记录投递失败，超过上限后转入死信状态。
     *
     * @param event 投递事件
     * @param error 脱敏错误摘要
     * @param maxAttempts 最大尝试次数
     * @param now 失败时间
     */
    public void markFailed(TaskOutboxEvent event, String error, int maxAttempts, Instant now) {
        boolean dead = event.attemptCount() >= Math.max(1, maxAttempts);
        long delaySeconds = Math.min(300L, 1L << Math.min(event.attemptCount(), 8));
        jdbcTemplate.update("""
                UPDATE task_outbox
                SET status = ?, next_attempt_at = ?, locked_at = NULL, last_error = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, dead ? "DEAD" : "RETRY", dead ? null : Timestamp.from(now.plusSeconds(delaySeconds)),
                error.substring(0, Math.min(error.length(), 1024)), event.id().toString());
    }

    /**
     * 将指定死信事件重置为待投递。
     *
     * @param eventId 事件标识
     * @param now 重投时间
     * @return 重置数量
     */
    public int replayEvent(UUID eventId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE task_outbox
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?, locked_at = NULL, last_error = NULL
                WHERE id = ? AND status = 'DEAD'
                """, Timestamp.from(now), eventId.toString());
    }

    /**
     * 将指定任务的全部死信事件重置为待投递。
     *
     * @param taskId 任务标识
     * @param now 重投时间
     * @return 重置数量
     */
    public int replayTask(UUID taskId, Instant now) {
        return jdbcTemplate.update("""
                UPDATE task_outbox
                SET status = 'PENDING', attempt_count = 0, next_attempt_at = ?, locked_at = NULL, last_error = NULL
                WHERE aggregate_type = 'TASK_INSTANCE' AND aggregate_id = ? AND status = 'DEAD'
                """, Timestamp.from(now), taskId.toString());
    }

    /**
     * Outbox 投递事件。
     *
     * @param id 事件标识
     * @param eventType 事件类型
     * @param payloadJson 事件载荷
     * @param attemptCount 本次尝试序号
     */
    public record TaskOutboxEvent(UUID id, String eventType, String payloadJson, int attemptCount) {
    }

    private record TaskMetadata(String taskName, int definitionVersion, String businessType, String businessId) {
    }
}
