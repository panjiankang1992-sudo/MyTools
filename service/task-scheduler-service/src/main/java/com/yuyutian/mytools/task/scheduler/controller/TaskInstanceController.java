package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskExecutionResultView;
import com.yuyutian.mytools.task.scheduler.service.TaskInstanceService;
import com.yuyutian.mytools.task.scheduler.service.TaskResultQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 任务实例控制器。
 */
@RestController
@RequestMapping("/api/v1/task-instances")
public class TaskInstanceController {

    private final TaskInstanceService taskInstanceService;
    private final TaskResultQueryService taskResultQueryService;

    /**
     * 创建任务实例控制器。
     *
     * @param taskInstanceService 任务实例服务
     * @param taskResultQueryService 任务结果查询服务
     */
    public TaskInstanceController(TaskInstanceService taskInstanceService,
                                  TaskResultQueryService taskResultQueryService) {
        this.taskInstanceService = taskInstanceService;
        this.taskResultQueryService = taskResultQueryService;
    }

    /**
     * 创建任务实例。
     *
     * @param request 创建请求
     * @return 任务实例
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskInstanceView create(@Valid @RequestBody CreateTaskRequest request) {
        return taskInstanceService.create(request);
    }

    /**
     * 查询任务实例。
     *
     * @param id 实例标识
     * @return 任务实例
     */
    @GetMapping("/{id}")
    public TaskInstanceView get(@PathVariable UUID id) {
        return taskInstanceService.get(id);
    }

    /**
     * 查询任务实例的步骤执行结果。
     *
     * @param id 实例标识
     * @return 执行结果
     */
    @GetMapping("/{id}/results")
    public TaskExecutionResultView getResults(@PathVariable UUID id) {
        return taskResultQueryService.get(id);
    }

    /**
     * 请求取消任务实例。
     *
     * @param id 实例标识
     * @return 更新后的任务实例
     */
    @PostMapping("/{id}/cancel")
    public TaskInstanceView cancel(@PathVariable UUID id) {
        return taskInstanceService.cancel(id);
    }
}
