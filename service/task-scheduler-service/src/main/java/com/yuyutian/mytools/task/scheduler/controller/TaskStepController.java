package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskStepRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskStepView;
import com.yuyutian.mytools.task.scheduler.service.TaskStepService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 任务步骤控制器。
 */
@RestController
@RequestMapping("/api/v1/task-definitions/{definitionId}/steps")
public class TaskStepController {

    private final TaskStepService service;

    /**
     * 创建任务步骤控制器。
     *
     * @param service 任务步骤服务
     */
    public TaskStepController(TaskStepService service) {
        this.service = service;
    }

    /**
     * 新增任务步骤。
     *
     * @param definitionId 定义标识
     * @param request 创建请求
     * @return 创建后的步骤
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskStepView create(@PathVariable UUID definitionId,
                               @Valid @RequestBody CreateTaskStepRequest request) {
        return service.create(definitionId, request);
    }

    /**
     * 查询任务步骤。
     *
     * @param definitionId 定义标识
     * @return 步骤列表
     */
    @GetMapping
    public List<TaskStepView> list(@PathVariable UUID definitionId) {
        return service.list(definitionId);
    }
}
