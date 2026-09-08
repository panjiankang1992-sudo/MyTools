package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.CreateChildTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.TaskInstanceView;
import com.yuyutian.mytools.task.scheduler.model.TaskExecutionResultView;
import com.yuyutian.mytools.task.scheduler.model.TaskCheckpointView;
import com.yuyutian.mytools.task.scheduler.model.WriteTaskCheckpointRequest;
import com.yuyutian.mytools.task.scheduler.service.TaskCheckpointService;
import com.yuyutian.mytools.task.scheduler.service.TaskScriptApiService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

/**
 * 脚本租约作用域任务接口控制器。
 */
@RestController
@RequestMapping("/internal/v1/executions/{executionId}/tasks")
public class TaskScriptApiController {

    private final TaskScriptApiService service;
    private final TaskCheckpointService checkpointService;

    /**
     * 创建脚本任务接口控制器。
     *
     * @param service 脚本任务接口服务
     * @param checkpointService 任务检查点服务
     */
    public TaskScriptApiController(TaskScriptApiService service, TaskCheckpointService checkpointService) {
        this.service = service;
        this.checkpointService = checkpointService;
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

    /**
     * 幂等写入当前任务检查点。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param checkpointKey 检查点键
     * @param request 写入请求
     * @return 写入后的检查点
     */
    @PutMapping("/checkpoints/{checkpointKey}")
    public TaskCheckpointView writeCheckpoint(@PathVariable UUID executionId,
                                               @RequestHeader("X-Task-Lease-Token") UUID leaseToken,
                                               @PathVariable String checkpointKey,
                                               @Valid @RequestBody WriteTaskCheckpointRequest request) {
        return checkpointService.write(executionId, leaseToken, checkpointKey, request);
    }

    /**
     * 读取当前任务检查点。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @param checkpointKey 检查点键
     * @return 检查点
     */
    @GetMapping("/checkpoints/{checkpointKey}")
    public TaskCheckpointView getCheckpoint(@PathVariable UUID executionId,
                                             @RequestHeader("X-Task-Lease-Token") UUID leaseToken,
                                             @PathVariable String checkpointKey) {
        return checkpointService.get(executionId, leaseToken, checkpointKey);
    }

    /**
     * 列举当前任务全部检查点。
     *
     * @param executionId 当前执行标识
     * @param leaseToken 租约令牌
     * @return 检查点列表
     */
    @GetMapping("/checkpoints")
    public List<TaskCheckpointView> listCheckpoints(@PathVariable UUID executionId,
                                                    @RequestHeader("X-Task-Lease-Token") UUID leaseToken) {
        return checkpointService.list(executionId, leaseToken);
    }
}
