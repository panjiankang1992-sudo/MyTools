package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.StepExecutionResultView;
import com.yuyutian.mytools.task.scheduler.model.TaskExecutionResultView;
import com.yuyutian.mytools.task.scheduler.model.TaskStatus;
import com.yuyutian.mytools.task.scheduler.repository.JsonColumnMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务执行结果查询服务。
 */
@Service
public class TaskResultQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonColumnMapper jsonColumnMapper;
    private final TaskInstanceService taskInstanceService;

    /**
     * 创建任务执行结果查询服务。
     *
     * @param jdbcTemplate JDBC 模板
     * @param jsonColumnMapper JSON 转换器
     * @param taskInstanceService 任务实例服务
     */
    public TaskResultQueryService(JdbcTemplate jdbcTemplate, JsonColumnMapper jsonColumnMapper,
                                  TaskInstanceService taskInstanceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonColumnMapper = jsonColumnMapper;
        this.taskInstanceService = taskInstanceService;
    }

    /**
     * 查询任务实例的全部步骤结果，包括失败尝试。
     *
     * @param taskInstanceId 任务实例标识
     * @return 执行结果
     */
    public TaskExecutionResultView get(UUID taskInstanceId) {
        var instance = taskInstanceService.get(taskInstanceId);
        List<StepExecutionResultView> steps = jdbcTemplate.query("""
                SELECT te.id AS execution_id, te.node_id, td.execution_mode,
                       et.target_index, et.target_count, ts.name AS step_name, se.attempt, se.status,
                       se.result_json, se.error_code, se.error_message, se.finished_at
                FROM task_execution te
                JOIN step_execution se ON se.task_execution_id = te.id
                JOIN task_step_definition ts ON ts.id = se.step_definition_id
                JOIN task_instance ti ON ti.id = te.task_instance_id
                JOIN task_definition td ON td.id = ti.task_definition_id
                LEFT JOIN task_execution_target et ON et.id = te.execution_target_id
                WHERE te.task_instance_id = ?
                ORDER BY te.created_at, ts.sequence_number, se.attempt
                """, (resultSet, rowNumber) -> {
            String resultJson = resultSet.getString("result_json");
            return new StepExecutionResultView(
                    UUID.fromString(resultSet.getString("execution_id")),
                    UUID.fromString(resultSet.getString("node_id")),
                    com.yuyutian.mytools.task.scheduler.model.ExecutionMode.valueOf(
                            resultSet.getString("execution_mode")),
                    resultSet.getObject("target_index", Integer.class),
                    resultSet.getObject("target_count", Integer.class), resultSet.getString("step_name"),
                    resultSet.getInt("attempt"), TaskStatus.valueOf(resultSet.getString("status")),
                    resultJson == null ? Map.of() : jsonColumnMapper.read(resultJson),
                    resultSet.getString("error_code"), resultSet.getString("error_message"),
                    resultSet.getTimestamp("finished_at").toInstant());
        }, taskInstanceId.toString());
        return new TaskExecutionResultView(taskInstanceId, instance.status(), steps);
    }
}
