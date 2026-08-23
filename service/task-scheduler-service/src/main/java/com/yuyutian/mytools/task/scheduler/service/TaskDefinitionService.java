package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 任务定义服务。
 */
@Service
public class TaskDefinitionService {

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
    }
}
