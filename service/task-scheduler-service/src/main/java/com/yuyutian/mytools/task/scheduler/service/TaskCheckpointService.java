package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.TaskCheckpointView;
import com.yuyutian.mytools.task.scheduler.model.WriteTaskCheckpointRequest;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提供租约作用域、幂等且受 fencing 保护的任务检查点读写。
 */
@Service
public class TaskCheckpointService {

    private static final int MAX_CHECKPOINT_BYTES = 256 * 1024;
    private static final int MAX_CHECKPOINT_COUNT = 128;

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建任务检查点服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     */
    public TaskCheckpointService(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 幂等写入当前执行所属任务的检查点。
     *
     * @param executionId 执行标识
     * @param leaseToken 租约令牌
     * @param key 检查点键
     * @param request 写入请求
     * @return 写入后的检查点
     */
    @Transactional
    public TaskCheckpointView write(UUID executionId, UUID leaseToken, String key,
                                    WriteTaskCheckpointRequest request) {
        validateKey(key);
        String checkpointJson = jsonColumnMapper.write(request.value());
        if (checkpointJson.getBytes(StandardCharsets.UTF_8).length > MAX_CHECKPOINT_BYTES) {
            throw new IllegalArgumentException("Task checkpoint exceeds the size limit");
        }
        ExecutionScope scope = requireActiveExecution(executionId, leaseToken);
        CheckpointWrite existingWrite = findWrite(request.requestId());
        if (existingWrite != null) {
            if (!existingWrite.taskId().equals(scope.taskId()) || !existingWrite.key().equals(key)
                    || existingWrite.expectedVersion() != request.expectedVersion()
                    || !existingWrite.value().equals(request.value())) {
                throw checkpointConflict("Checkpoint request identifier conflicts with the stored write");
            }
            return new TaskCheckpointView(existingWrite.key(), existingWrite.appliedVersion(),
                    existingWrite.value(), existingWrite.createdAt(), true);
        }

        CheckpointRow current = findForUpdate(scope.taskId(), key);
        if (current == null) {
            if (request.expectedVersion() != 0) {
                throw checkpointConflict("Checkpoint does not exist at the expected version");
            }
            // 锁定任务行，串行化不同 key 的首次写入，避免并发突破单任务检查点数量上限。
            jdbcTemplate.queryForObject("SELECT id FROM task_instance WHERE id = ? FOR UPDATE",
                    String.class, scope.taskId().toString());
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_checkpoint WHERE task_instance_id = ?",
                    Integer.class, scope.taskId().toString());
            if (count != null && count >= MAX_CHECKPOINT_COUNT) {
                throw new IllegalStateException("Task checkpoint count limit was reached");
            }
        } else {
            if (current.fencingToken() > scope.fencingToken()) {
                throw checkpointConflict("Checkpoint was written by a newer execution fence");
            }
            if (current.version() != request.expectedVersion()) {
                throw checkpointConflict("Checkpoint version changed concurrently");
            }
        }

        long appliedVersion = request.expectedVersion() + 1;
        Instant now = Instant.now();
        try {
            if (current == null) {
                jdbcTemplate.update("""
                        INSERT INTO task_checkpoint
                        (task_instance_id, checkpoint_key, checkpoint_json, updated_at, checkpoint_version,
                         fencing_token, execution_id, last_request_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, scope.taskId().toString(), key, checkpointJson, Timestamp.from(now), appliedVersion,
                        scope.fencingToken(), executionId.toString(), request.requestId().toString());
            } else {
                int updated = jdbcTemplate.update("""
                        UPDATE task_checkpoint
                        SET checkpoint_json = ?, updated_at = ?, checkpoint_version = ?, fencing_token = ?,
                            execution_id = ?, last_request_id = ?
                        WHERE task_instance_id = ? AND checkpoint_key = ?
                          AND checkpoint_version = ? AND fencing_token <= ?
                        """, checkpointJson, Timestamp.from(now), appliedVersion, scope.fencingToken(),
                        executionId.toString(), request.requestId().toString(), scope.taskId().toString(), key,
                        request.expectedVersion(), scope.fencingToken());
                if (updated != 1) {
                    throw checkpointConflict("Checkpoint version or execution fence changed concurrently");
                }
            }
            jdbcTemplate.update("""
                    INSERT INTO task_checkpoint_write
                    (request_id, task_instance_id, checkpoint_key, expected_version, applied_version,
                     checkpoint_json, fencing_token, execution_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, request.requestId().toString(), scope.taskId().toString(), key,
                    request.expectedVersion(), appliedVersion, checkpointJson, scope.fencingToken(),
                    executionId.toString(), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw checkpointConflict("Checkpoint was written concurrently");
        }
        return new TaskCheckpointView(key, appliedVersion, jsonColumnMapper.read(checkpointJson), now, false);
    }

    /**
     * 读取当前执行所属任务的一个检查点。
     *
     * @param executionId 执行标识
     * @param leaseToken 租约令牌
     * @param key 检查点键
     * @return 检查点
     */
    public TaskCheckpointView get(UUID executionId, UUID leaseToken, String key) {
        validateKey(key);
        ExecutionScope scope = requireActiveExecution(executionId, leaseToken);
        return jdbcTemplate.query("""
                SELECT checkpoint_key, checkpoint_version, checkpoint_json, updated_at
                FROM task_checkpoint WHERE task_instance_id = ? AND checkpoint_key = ?
                """, (resultSet, rowNumber) -> new TaskCheckpointView(
                resultSet.getString("checkpoint_key"), resultSet.getLong("checkpoint_version"),
                jsonColumnMapper.read(resultSet.getString("checkpoint_json")),
                resultSet.getTimestamp("updated_at").toInstant(), false), scope.taskId().toString(), key)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task checkpoint does not exist"));
    }

    /**
     * 列举当前执行所属任务的检查点。
     *
     * @param executionId 执行标识
     * @param leaseToken 租约令牌
     * @return 检查点列表
     */
    public List<TaskCheckpointView> list(UUID executionId, UUID leaseToken) {
        ExecutionScope scope = requireActiveExecution(executionId, leaseToken);
        return jdbcTemplate.query("""
                SELECT checkpoint_key, checkpoint_version, checkpoint_json, updated_at
                FROM task_checkpoint WHERE task_instance_id = ? ORDER BY checkpoint_key
                """, (resultSet, rowNumber) -> new TaskCheckpointView(
                resultSet.getString("checkpoint_key"), resultSet.getLong("checkpoint_version"),
                jsonColumnMapper.read(resultSet.getString("checkpoint_json")),
                resultSet.getTimestamp("updated_at").toInstant(), false), scope.taskId().toString());
    }

    private ExecutionScope requireActiveExecution(UUID executionId, UUID leaseToken) {
        return jdbcTemplate.query("""
                SELECT execution.task_instance_id, execution.fencing_token
                FROM task_execution execution
                JOIN task_instance task ON task.id = execution.task_instance_id
                WHERE execution.id = ? AND execution.lease_token = ?
                  AND execution.status = 'RUNNING' AND execution.lease_until >= ?
                  AND task.status IN ('RUNNING', 'WAITING_CHILDREN')
                """, (resultSet, rowNumber) -> new ExecutionScope(
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getLong("fencing_token")), executionId.toString(), leaseToken.toString(),
                Timestamp.from(Instant.now())).stream().findFirst()
                .orElseThrow(() -> new SchedulerException(ErrorCode.EXECUTION_LEASE_LOST,
                        HttpStatus.CONFLICT, "Active execution lease does not exist"));
    }

    private CheckpointRow findForUpdate(UUID taskId, String key) {
        return jdbcTemplate.query("""
                SELECT checkpoint_version, fencing_token FROM task_checkpoint
                WHERE task_instance_id = ? AND checkpoint_key = ? FOR UPDATE
                """, (resultSet, rowNumber) -> new CheckpointRow(
                resultSet.getLong("checkpoint_version"), resultSet.getLong("fencing_token")),
                taskId.toString(), key).stream().findFirst().orElse(null);
    }

    private CheckpointWrite findWrite(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT task_instance_id, checkpoint_key, expected_version, applied_version,
                       checkpoint_json, created_at
                FROM task_checkpoint_write WHERE request_id = ?
                """, (resultSet, rowNumber) -> new CheckpointWrite(
                UUID.fromString(resultSet.getString("task_instance_id")),
                resultSet.getString("checkpoint_key"), resultSet.getLong("expected_version"),
                resultSet.getLong("applied_version"),
                jsonColumnMapper.read(resultSet.getString("checkpoint_json")),
                resultSet.getTimestamp("created_at").toInstant()), requestId.toString())
                .stream().findFirst().orElse(null);
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")) {
            throw new IllegalArgumentException("Task checkpoint key is invalid");
        }
    }

    private SchedulerException checkpointConflict(String message) {
        return new SchedulerException(ErrorCode.CHECKPOINT_CONFLICT, HttpStatus.CONFLICT, message);
    }

    private record ExecutionScope(UUID taskId, long fencingToken) {
    }

    private record CheckpointRow(long version, long fencingToken) {
    }

    private record CheckpointWrite(UUID taskId, String key, long expectedVersion, long appliedVersion,
                                   Map<String, Object> value, Instant createdAt) {
    }
}
