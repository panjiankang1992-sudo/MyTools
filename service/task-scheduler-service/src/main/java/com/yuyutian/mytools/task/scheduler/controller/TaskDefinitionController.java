package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.CreateTaskDefinitionRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskDefinitionView;
import com.yuyutian.mytools.task.scheduler.service.TaskDefinitionService;
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
 * 任务定义控制器。
 */
@RestController
@RequestMapping("/api/v1/task-definitions")
public class TaskDefinitionController {

    private final TaskDefinitionService service;

    /**
     * 创建任务定义控制器。
     *
     * @param service 任务定义服务
     */
    public TaskDefinitionController(TaskDefinitionService service) {
        this.service = service;
    }

    /**
     * 创建任务定义。
     *
     * @param request 创建请求
     * @return 创建后的定义
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDefinitionView create(@Valid @RequestBody CreateTaskDefinitionRequest request) {
        return service.create(request);
    }

    /**
     * 查询任务定义。
     *
     * @param id 定义标识
     * @return 任务定义
     */
    @GetMapping("/{id}")
    public TaskDefinitionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 查询全部任务定义。
     *
     * @return 定义列表
     */
    @GetMapping
    public List<TaskDefinitionView> list() {
        return service.list();
    }
}
