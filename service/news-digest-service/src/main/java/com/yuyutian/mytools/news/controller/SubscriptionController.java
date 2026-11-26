package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.SchedulingModels.*;
import com.yuyutian.mytools.news.service.SchedulingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.yuyutian.mytools.news.config.SchedulingProperties;
import java.util.UUID;

/** 订阅与运行查询始终使用 Gateway 注入的所有者。 */
@RestController
@Validated
@RequestMapping("/internal/v1/news")
public class SubscriptionController {
    private final SchedulingService service;
    private final SchedulingProperties properties;
    private final com.yuyutian.mytools.news.config.DeliveryProperties delivery;
    /** 创建订阅入口。 */
    public SubscriptionController(SchedulingService service, SchedulingProperties properties, com.yuyutian.mytools.news.config.DeliveryProperties delivery) { this.service = service; this.properties = properties; this.delivery=delivery; }
    /** 首次配置前返回空订阅，并明确显示自动编排是否启用。 */
    @GetMapping("/subscription")
    public SubscriptionState subscription(@RequestParam @Min(1) long ownerId) {
        return new SubscriptionState(service.subscription(ownerId).orElse(null), properties.enabled(), delivery.enabled());
    }
    /** 使用预期版本创建或更新订阅。 */
    @PutMapping("/subscription")
    public Subscription change(@RequestParam @Min(1) long ownerId, @Valid @RequestBody SubscriptionChange request) { return service.change(ownerId, request); }
    /** 查询本人最近运行，包含跳过和失败状态。 */
    @GetMapping("/runs")
    public List<DailyRun> runs(@RequestParam @Min(1) long ownerId, @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) { return service.runs(ownerId, limit); }
    /** 查询本人单次运行。 */
    @GetMapping("/runs/{id}")
    public DailyRun run(@RequestParam @Min(1) long ownerId, @PathVariable UUID id) { return service.run(ownerId, id); }
    /** 请求取消本人运行。 */
    @PostMapping("/runs/{id}/cancel")
    public DailyRun cancel(@RequestParam @Min(1) long ownerId, @PathVariable UUID id) { return service.cancel(ownerId, id); }
    /** 内容订阅与消息投递能力分别展示。 */
    public record SubscriptionState(Subscription subscription, boolean schedulingEnabled, boolean deliveryEnabled) { }
}
