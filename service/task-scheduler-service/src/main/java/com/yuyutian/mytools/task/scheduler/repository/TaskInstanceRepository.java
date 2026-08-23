package com.yuyutian.mytools.task.scheduler.repository;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务实例持久化仓储。
 */
@Repository
public class TaskInstanceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建任务实例仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     */
    public TaskInstanceRepository(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 新增任务实例。
     *
     * @param request 创建请求
     * @param definition 任务定义版本
     * @return 创建后的实例
     */
    public TaskInstanceView insert(CreateTaskRequest request, TaskDefinitionView definition) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO task_instance (
                    id, task_definition_id, task_definition_version, task_name, idempotency_key,
                    parent_task_instance_id, business_type, business_id, priority, parameters_json,
                    status, progress, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(), definition.id().toString(), definition.version(), definition.name(),
                request.idempotencyKey(), uuidText(request.parentTaskInstanceId()), request.businessType(),
                request.businessId(), request.priority(), jsonColumnMapper.write(request.parameters()),
                TaskStatus.QUEUED.name(), 0, Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    /**
     * 按标识查询实例。
     *
     * @param id 实例标识
     * @return 可选实例
     */
    public Optional<TaskInstanceView> findById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM task_instance WHERE id = ?", this::mapRow, id.toString())
                .stream().findFirst();
    }

    /**
     * 按幂等键查询实例。
     *
     * @param idempotencyKey 幂等键
     * @return 可选实例
     */
    public Optional<TaskInstanceView> findByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query("SELECT * FROM task_instance WHERE idempotency_key = ?", this::mapRow, idempotencyKey)
                .stream().findFirst();
    }

    /**
     * 更新任务状态。
     *
     * @param id 实例标识
     * @param expectedStatus 预期原状态
     * @param targetStatus 目标状态
     * @param cancelRequestedAt 取消请求时间
     * @return 是否成功更新
     */
    public boolean updateStatus(UUID id, TaskStatus expectedStatus, TaskStatus targetStatus, Instant cancelRequestedAt) {
        return jdbcTemplate.update("""
                UPDATE task_instance
                SET status = ?, cancel_requested_at = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """, targetStatus.name(), timestamp(cancelRequestedAt), Timestamp.from(Instant.now()),
                id.toString(), expectedStatus.name()) == 1;
    }

    private TaskInstanceView mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String parentId = resultSet.getString("parent_task_instance_id");
        return new TaskInstanceView(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("task_name"),
                resultSet.getString("idempotency_key"), parentId == null ? null : UUID.fromString(parentId),
                resultSet.getString("business_type"), resultSet.getString("business_id"),
                resultSet.getInt("priority"), jsonColumnMapper.read(resultSet.getString("parameters_json")),
                TaskStatus.valueOf(resultSet.getString("status")), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private String uuidText(UUID id) {
        return id == null ? null : id.toString();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
