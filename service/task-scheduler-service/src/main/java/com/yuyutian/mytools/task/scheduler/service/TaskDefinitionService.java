package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 任务定义服务。
 */
@Service
public class TaskDefinitionService {

    private static final Set<String> OVERLAP_POLICIES = Set.of("ALLOW", "SKIP", "QUEUE", "REPLACE");
    private static final Set<String> MISFIRE_POLICIES = Set.of("IGNORE", "RUN_ONCE", "CATCH_UP");

    private final TaskDefinitionRepository repository;

    /**
     * 创建任务定义服务。
     *
     * @param repository 定义仓储
     */
    public TaskDefinitionService(TaskDefinitionRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建任务定义。
     *
     * @param request 创建请求
     * @return 创建后的定义
     */
    @Transactional
    public TaskDefinitionView create(CreateTaskDefinitionRequest request) {
        validateSchedule(request);
        try {
            return repository.insert(request);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Task definition already exists", exception);
        }
    }

    /**
     * 查询任务定义。
     *
     * @param id 定义标识
     * @return 任务定义
     */
    public TaskDefinitionView get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Task definition does not exist"));
    }

    /**
     * 查询全部任务定义。
     *
     * @return 定义列表
     */
    public List<TaskDefinitionView> list() {
        return repository.findAll();
    }

    private void validateSchedule(CreateTaskDefinitionRequest request) {
        if (request.taskType() == com.yuyutian.mytools.task.scheduler.model.TaskType.SCHEDULED
                && (request.cronExpression() == null || request.cronExpression().isBlank())) {
            throw new IllegalArgumentException("Scheduled task requires a cron expression");
        }
        if (request.taskType() == com.yuyutian.mytools.task.scheduler.model.TaskType.IMMEDIATE
                && request.cronExpression() != null && !request.cronExpression().isBlank()) {
            throw new IllegalArgumentException("Immediate task cannot define a cron expression");
        }
        if (!OVERLAP_POLICIES.contains(request.overlapPolicy())) {
            throw new IllegalArgumentException("Unsupported overlap policy");
        }
        if (!MISFIRE_POLICIES.contains(request.misfirePolicy())) {
            throw new IllegalArgumentException("Unsupported misfire policy");
        }
        if (request.taskType() == com.yuyutian.mytools.task.scheduler.model.TaskType.SCHEDULED) {
            try {
                CronExpression.parse(request.cronExpression());
                ZoneId.of(request.cronTimezone() == null || request.cronTimezone().isBlank()
                        ? "UTC" : request.cronTimezone());
            } catch (IllegalArgumentException | DateTimeException exception) {
                throw new IllegalArgumentException("Scheduled task cron or timezone is invalid", exception);
            }
        }
    }
}
