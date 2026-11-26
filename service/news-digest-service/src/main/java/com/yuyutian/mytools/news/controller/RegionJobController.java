package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.PipelineModels.RegionJobReceipt;
import com.yuyutian.mytools.news.Model.RegionModels.RegionObservation;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionProof;
import com.yuyutian.mytools.news.service.RegionJobService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 地区自动探测只接受独立工作令牌和活动执行租约。 */
@RestController
@RequestMapping("/internal/jobs/news/region-jobs")
public class RegionJobController {
    private final RegionJobService service;
    /** 创建地区作业入口。 */
    public RegionJobController(RegionJobService service) { this.service = service; }
    /** 探测前验证租约并查询原回执，已完成的步骤无需再次访问供应商。 */
    @PostMapping("/{id}/receipt")
    public ReceiptState receipt(@PathVariable UUID id, @RequestHeader("X-Task-Execution-Id") UUID executionId,
                                @RequestHeader("X-Task-Lease-Token") UUID leaseToken) {
        return new ReceiptState(service.receipt(id, new ExecutionProof(executionId, leaseToken)).orElse(null));
    }
    /** 提交去标识报告并原子完成作业。 */
    @PostMapping("/{id}/observation")
    public RegionJobReceipt observe(@PathVariable UUID id, @RequestHeader("X-Task-Execution-Id") UUID executionId,
                                    @RequestHeader("X-Task-Lease-Token") UUID leaseToken, @Valid @RequestBody RegionObservation report) {
        return service.observe(id, new ExecutionProof(executionId, leaseToken), report);
    }
    /** 回执不存在时允许当前执行继续探测，不返回原始观测。 */
    public record ReceiptState(RegionJobReceipt receipt) { }
}
