package com.yuyutian.mytools.news.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.SchedulingModels.ExecutionProof;
import com.yuyutian.mytools.news.Model.WeeklyModels.Context;
import com.yuyutian.mytools.news.service.WeeklyService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 工作入口同时要求独立工作令牌和 Scheduler 当前有效的在线执行租约。 */
@RestController
@RequestMapping("/internal/jobs/news/weekly/{dispatchId}")
public class WeeklyJobController {
    private final WeeklyService service;
    /** 创建受执行身份保护的工作入口。 */
    public WeeklyJobController(WeeklyService service) { this.service=service; }
    /** 接收有界原图，拒绝任意文件格式和外部网址。 */
    @PostMapping(value="/assets",consumes="application/octet-stream") public java.util.Map<String,String> asset(@PathVariable UUID dispatchId,
            @RequestHeader("X-Task-Execution-Id") UUID execution,@RequestHeader("X-Task-Lease-Token") UUID lease,@RequestBody byte[] bytes) {
        return service.upload(dispatchId,new ExecutionProof(execution,lease),bytes);
    }
    /** 取得固定窗口与任务身份。 */
    @PostMapping("/context") public Context context(@PathVariable UUID dispatchId,
            @RequestHeader("X-Task-Execution-Id") UUID execution,@RequestHeader("X-Task-Lease-Token") UUID lease) {
        return service.context(dispatchId,new ExecutionProof(execution,lease));
    }
    /** 更新真实阶段和已完成分类数。 */
    @PostMapping("/progress") public Context progress(@PathVariable UUID dispatchId,
            @RequestHeader("X-Task-Execution-Id") UUID execution,@RequestHeader("X-Task-Lease-Token") UUID lease,@RequestBody JsonNode body) {
        return service.progress(dispatchId,new ExecutionProof(execution,lease),body);
    }
    /** 原子发布完整或部分完成的报告。 */
    @PostMapping("/report") public Context report(@PathVariable UUID dispatchId,
            @RequestHeader("X-Task-Execution-Id") UUID execution,@RequestHeader("X-Task-Lease-Token") UUID lease,@RequestBody JsonNode body) {
        return service.publish(dispatchId,new ExecutionProof(execution,lease),body);
    }
}
