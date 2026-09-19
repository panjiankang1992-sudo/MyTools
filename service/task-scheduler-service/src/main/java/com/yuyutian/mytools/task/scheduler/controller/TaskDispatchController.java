package com.yuyutian.mytools.task.scheduler.controller;

import com.yuyutian.mytools.task.scheduler.model.ClaimTaskRequest;
import com.yuyutian.mytools.task.scheduler.model.CompleteExecutionRequest;
import com.yuyutian.mytools.task.scheduler.model.ExecutionReportView;
import com.yuyutian.mytools.task.scheduler.model.LeaseHeartbeatRequest;
import com.yuyutian.mytools.task.scheduler.model.WorkloadTransport;
import com.yuyutian.mytools.task.scheduler.model.ReportStepExecutionRequest;
import com.yuyutian.mytools.task.scheduler.service.TaskDispatchService;
import com.yuyutian.mytools.task.scheduler.service.ClaimCapacityReservedException;
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
    public ResponseEntity<WorkloadTransport.Claim> claim(@Valid @RequestBody ClaimTaskRequest request) {
        try {
            return service.claim(request).map(value -> ResponseEntity.ok().header("Cache-Control", "no-store, private")
                    .body(new WorkloadTransport.Claim(value))).orElseGet(() -> ResponseEntity.noContent().build());
        } catch (ClaimCapacityReservedException exception) {
            // 二百零四保持旧 Executor 兼容，新 Executor 通过响应头停止浅层回退。
            return ResponseEntity.noContent()
                    .header("X-MyTools-Claim-Blocked", "CAPACITY_RESERVED")
                    .build();
        }
    }

    /**
     * 续期任务租约。
     *
     * @param executionId 执行标识
     * @param request 续期请求
     * @return 租约状态
     */
    @PostMapping("/{executionId}/heartbeat")
    public ResponseEntity<WorkloadTransport.Heartbeat> heartbeat(@PathVariable UUID executionId,
                                        @Valid @RequestBody LeaseHeartbeatRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store, private")
                .body(new WorkloadTransport.Heartbeat(service.heartbeat(executionId, request)));
    }

    /**
     * 上报步骤执行结果。
     *
     * @param executionId 执行标识
     * @param request 步骤结果
     */
    @PostMapping("/{executionId}/steps/report")
    public ExecutionReportView reportStep(@PathVariable UUID executionId,
                                          @Valid @RequestBody ReportStepExecutionRequest request) {
        return service.reportStep(executionId, request);
    }

    /**
     * 完成任务执行。
     *
     * @param executionId 执行标识
     * @param request 完成请求
     */
    @PostMapping("/{executionId}/complete")
    public ExecutionReportView complete(@PathVariable UUID executionId,
                                        @Valid @RequestBody CompleteExecutionRequest request) {
        return service.complete(executionId, request);
    }
}
