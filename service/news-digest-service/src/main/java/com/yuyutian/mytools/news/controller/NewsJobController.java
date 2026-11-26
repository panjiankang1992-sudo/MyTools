package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.service.SchedulingService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 节点作业入口使用独立令牌与实时执行租约，用户代理和地区令牌均不能调用。 */
@RestController
@RequestMapping("/internal/jobs/news/runs")
public class NewsJobController {
    private final SchedulingService service;
    /** 创建执行边界。 */
    public NewsJobController(SchedulingService service) { this.service = service; }
    /** 任务参数只含运行标识，所有者、时区、截止与节点全部读取服务端冻结记录。 */
    @PostMapping("/{id}/generate")
    public GenerationReceipt generate(@PathVariable UUID id, @RequestHeader("X-Task-Execution-Id") UUID executionId,
                                     @RequestHeader("X-Task-Lease-Token") UUID leaseToken) {
        return service.generate(id, new ExecutionProof(executionId, leaseToken));
    }
}
