package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskStepView;
import com.yuyutian.mytools.task.scheduler.repository.TaskDefinitionRepository;
import com.yuyutian.mytools.task.scheduler.repository.TaskStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 任务步骤服务。
 */
@Service
public class TaskStepService {

    private final TaskDefinitionRepository definitionRepository;
    private final TaskStepRepository stepRepository;

    /**
     * 创建任务步骤服务。
     *
     * @param definitionRepository 任务定义仓储
     * @param stepRepository 步骤仓储
     */
    public TaskStepService(TaskDefinitionRepository definitionRepository, TaskStepRepository stepRepository) {
        this.definitionRepository = definitionRepository;
        this.stepRepository = stepRepository;
    }

    /**
     * 创建任务步骤。
     *
     * @param definitionId 定义标识
     * @param request 创建请求
     * @return 创建后的步骤
     */
    @Transactional
    public TaskStepView create(UUID definitionId, CreateTaskStepRequest request) {
        definitionRepository.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Task definition does not exist"));
        return stepRepository.insert(definitionId, request);
    }

    /**
     * 查询任务步骤。
     *
     * @param definitionId 定义标识
     * @return 步骤列表
     */
    public List<TaskStepView> list(UUID definitionId) {
        definitionRepository.findById(definitionId)
                .orElseThrow(() -> new IllegalArgumentException("Task definition does not exist"));
        return stepRepository.list(definitionId);
    }
}
