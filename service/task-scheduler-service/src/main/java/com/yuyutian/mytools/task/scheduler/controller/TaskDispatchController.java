package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.ClaimedTaskView;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatView;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.service.TaskDispatchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 任务分发内部接口控制器。
 */
@RestController
@RequestMapping("/internal/v1/executions")
public class TaskDispatchController {

    private final TaskDispatchService service;

    /**
     * 创建任务分发控制器。
     *
     * @param service 任务分发服务
     */
    public TaskDispatchController(TaskDispatchService service) {
        this.service = service;
    }

    /**
     * 领取一个任务。
     *
     * @param request 领取请求
     * @return 任务或无内容响应
     */
    @PostMapping("/claim")
    public ResponseEntity<ClaimedTaskView> claim(@Valid @RequestBody ClaimTaskRequest request) {
        return service.claim(request).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 续期任务租约。
     *
     * @param executionId 执行标识
     * @param request 续期请求
     * @return 租约状态
     */
    @PostMapping("/{executionId}/heartbeat")
    public LeaseHeartbeatView heartbeat(@PathVariable UUID executionId,
                                        @Valid @RequestBody LeaseHeartbeatRequest request) {
        return service.heartbeat(executionId, request);
    }

    /**
     * 上报步骤执行结果。
     *
     * @param executionId 执行标识
     * @param request 步骤结果
     */
    @PostMapping("/{executionId}/steps/report")
    public void reportStep(@PathVariable UUID executionId,
                           @Valid @RequestBody ReportStepExecutionRequest request) {
        service.reportStep(executionId, request);
    }

    /**
     * 完成任务执行。
     *
     * @param executionId 执行标识
     * @param request 完成请求
     */
    @PostMapping("/{executionId}/complete")
    public void complete(@PathVariable UUID executionId,
                         @Valid @RequestBody CompleteExecutionRequest request) {
        service.complete(executionId, request);
    }
}
