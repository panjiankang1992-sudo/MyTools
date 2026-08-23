package com.yuyutian.mytools.task.scheduler.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.FailurePolicy;
import com.yuyutian.mytools.task.scheduler.model.StepKind;
import com.yuyutian.mytools.task.scheduler.model.TaskStepView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 任务步骤持久化仓储。
 */
@Repository
public class TaskStepRepository {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建任务步骤仓储。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 映射器
     */
    public TaskStepRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增任务步骤。
     *
     * @param definitionId 任务定义标识
     * @param request 创建请求
     * @return 新增后的步骤
     */
    public TaskStepView insert(UUID definitionId, CreateTaskStepRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO task_step_definition (
                    id, task_definition_id, name, description, step_kind, script_package, script_version,
                    entrypoint, arguments_template, enabled, timeout_seconds, failure_policy,
                    sequence_number, max_attempts, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), definitionId.toString(), request.name(), request.description(),
                request.stepKind().name(), request.scriptPackage(), request.scriptVersion(), request.entrypoint(),
                writeArguments(request.argumentsTemplate()), request.enabled(), request.timeoutSeconds(),
                request.failurePolicy().name(), request.sequenceNumber(), request.maxAttempts(),
                Timestamp.from(now), Timestamp.from(now));
        return list(definitionId).stream().filter(step -> step.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * 查询任务定义的全部步骤。
     *
     * @param definitionId 任务定义标识
     * @return 步骤列表
     */
    public List<TaskStepView> list(UUID definitionId) {
        return jdbcTemplate.query("""
                SELECT * FROM task_step_definition
                WHERE task_definition_id = ?
                ORDER BY step_kind, sequence_number, created_at
                """, this::mapRow, definitionId.toString());
    }

    private TaskStepView mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskStepView(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("task_definition_id")), resultSet.getString("name"),
                resultSet.getString("description"), StepKind.valueOf(resultSet.getString("step_kind")),
                resultSet.getString("script_package"), resultSet.getString("script_version"),
                resultSet.getString("entrypoint"), readArguments(resultSet.getString("arguments_template")),
                resultSet.getBoolean("enabled"), resultSet.getLong("timeout_seconds"),
                FailurePolicy.valueOf(resultSet.getString("failure_policy")), resultSet.getInt("sequence_number"),
                resultSet.getInt("max_attempts"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private String writeArguments(List<String> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Arguments cannot be serialized", exception);
        }
    }

    private List<String> readArguments(String value) {
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored arguments cannot be parsed", exception);
        }
    }
}
