package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskExecutionResultView;
import com.yuyutian.mytools.task.scheduler.service.TaskScriptApiService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 脚本租约作用域任务接口控制器。
 */
@RestController
@RequestMapping("/internal/v1/executions/{executionId}/tasks")
public class TaskScriptApiController {

    private final TaskScriptApiService service;

    /**
     * 创建脚本任务接口控制器。
     *
     * @param service 脚本任务接口服务
     */
    public TaskScriptApiController(TaskScriptApiService service) {
        this.service = service;
    }

    /**
     * 创建直接子任务。
     *
     * @param executionId 当前执行标识
     * @param request 创建请求
     * @return 子任务实例
     */
    @PostMapping("/children")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskInstanceView createChild(@PathVariable UUID executionId,
                                        @Valid @RequestBody CreateChildTaskRequest request) {
        return service.createChild(executionId, request);
    }

    /**
     * 查询当前任务或直接子任务。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param taskId 目标任务标识
     * @return 任务实例
     */
    @GetMapping("/{taskId}")
    public TaskInstanceView get(@PathVariable UUID executionId,
                                @RequestHeader("X-Task-Lease-Token") UUID leaseToken,
                                @PathVariable UUID taskId) {
        return service.getRelated(executionId, leaseToken, taskId);
    }

    /**
     * 查询当前任务或直接子任务的步骤结果。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param taskId 目标任务标识
     * @return 任务步骤结果
     */
    @GetMapping("/{taskId}/results")
    public TaskExecutionResultView getResults(@PathVariable UUID executionId,
                                              @RequestHeader("X-Task-Lease-Token") UUID leaseToken,
                                              @PathVariable UUID taskId) {
        return service.getRelatedResults(executionId, leaseToken, taskId);
    }

    /**
     * 取消直接子任务。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param taskId 子任务标识
     * @return 子任务实例
     */
    @PostMapping("/{taskId}/cancel")
    public TaskInstanceView cancel(@PathVariable UUID executionId,
                                   @RequestHeader("X-Task-Lease-Token") UUID leaseToken,
                                   @PathVariable UUID taskId) {
        return service.cancelChild(executionId, leaseToken, taskId);
    }
}
