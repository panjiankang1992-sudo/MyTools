package com.yuyutian.mytools.task.scheduler.repository;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionMode;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.model.TaskType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务定义持久化仓储。
 */
@Repository
public class TaskDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;

    /**
     * 创建任务定义仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     */
    public TaskDefinitionRepository(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
    }

    /**
     * 新增首个任务定义版本。
     *
     * @param request 创建请求
     * @return 创建后的定义
     */
    public TaskDefinitionView insert(CreateTaskDefinitionRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO task_definition (
                    id, name, description, task_type, timeout_seconds, cluster_id, cron_expression,
                    cron_timezone, execution_mode, enabled, max_concurrency, overlap_policy,
                    misfire_policy, parameter_schema, result_schema, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(), request.name(), request.description(), request.taskType().name(),
                request.timeoutSeconds(), uuidText(request.clusterId()), request.cronExpression(),
                request.cronTimezone(), request.executionMode().name(), request.enabled(),
                request.maxConcurrency(), request.overlapPolicy(), request.misfirePolicy(),
                jsonColumnMapper.write(request.parameterSchema()), jsonColumnMapper.write(request.resultSchema()),
                1, Timestamp.from(now), Timestamp.from(now));
        return findById(id).orElseThrow();
    }

    /**
     * 按标识查询任务定义。
     *
     * @param id 定义标识
     * @return 可选任务定义
     */
    public Optional<TaskDefinitionView> findById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM task_definition WHERE id = ?", this::mapRow, id.toString())
                .stream().findFirst();
    }

    /**
     * 查询最新启用版本。
     *
     * @param name 任务名称
     * @return 可选任务定义
     */
    public Optional<TaskDefinitionView> findLatestEnabled(String name) {
        return jdbcTemplate.query("""
                SELECT * FROM task_definition
                WHERE name = ? AND enabled = TRUE
                ORDER BY version DESC LIMIT 1
                """, this::mapRow, name).stream().findFirst();
    }

    /**
     * 查询全部任务定义。
     *
     * @return 定义列表
     */
    public List<TaskDefinitionView> findAll() {
        return jdbcTemplate.query("SELECT * FROM task_definition ORDER BY name, version DESC", this::mapRow);
    }

    private TaskDefinitionView mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String clusterId = resultSet.getString("cluster_id");
        return new TaskDefinitionView(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("name"),
                resultSet.getString("description"), TaskType.valueOf(resultSet.getString("task_type")),
                resultSet.getLong("timeout_seconds"), clusterId == null ? null : UUID.fromString(clusterId),
                resultSet.getString("cron_expression"), resultSet.getString("cron_timezone"),
                ExecutionMode.valueOf(resultSet.getString("execution_mode")), resultSet.getBoolean("enabled"),
                resultSet.getInt("max_concurrency"), resultSet.getString("overlap_policy"),
                resultSet.getString("misfire_policy"), jsonColumnMapper.read(resultSet.getString("parameter_schema")),
                jsonColumnMapper.read(resultSet.getString("result_schema")), resultSet.getInt("version"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private String uuidText(UUID id) {
        return id == null ? null : id.toString();
    }
}
