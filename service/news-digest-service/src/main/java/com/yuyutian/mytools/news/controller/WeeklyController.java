package com.yuyutian.mytools.news.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.news.Model.NewsModels.Category;
import com.yuyutian.mytools.news.Model.WeeklyModels.*;
import com.yuyutian.mytools.news.service.WeeklyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/** 手动周报的用户代理接口，所有者仅由已认证 Gateway 提供。 */
@Validated
@RestController
@RequestMapping("/internal/v1/news")
public class WeeklyController {
    private final WeeklyService service;
    /** 注入手动任务服务。 */
    public WeeklyController(WeeklyService service) { this.service=service; }
    /** 返回报告内受所有者隔离的原图，禁止共享缓存跨账号复用。 */
    @GetMapping("/weekly-reports/{id}/assets/{assetId}") public ResponseEntity<com.yuyutian.mytools.news.service.WeeklyAssets.Asset> image(
            @RequestParam @Min(1) long ownerId,@PathVariable UUID id,@PathVariable @Pattern(regexp="[a-f0-9]{64}") String assetId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(service.image(ownerId,id,assetId));
    }
    /** 查询手动能力，不触发采集。 */
    @GetMapping("/weekly-capabilities") public Map<String,Object> capabilities() { return service.capabilities(); }
    /** 快速受理请求，重复已完成任务返回二百，其余返回已受理。 */
    @PostMapping("/weekly-jobs") public ResponseEntity<View> create(@RequestParam @Min(1) long ownerId, @Valid @RequestBody Create request) {
        View result=service.create(ownerId,request);
        return ResponseEntity.status(result.reused() && !java.util.Set.of("QUEUED","RUNNING").contains(result.status()) ? 200 : 202).body(result);
    }
    /** 返回活动任务或显式空值。 */
    @GetMapping("/weekly-jobs/active") public Map<String,Object> active(@RequestParam @Min(1) long ownerId) {
        return java.util.Collections.singletonMap("job",service.active(ownerId));
    }
    /** 查询本人任务。 */
    @GetMapping("/weekly-jobs/{id}") public View job(@RequestParam @Min(1) long ownerId, @PathVariable UUID id) { return service.job(ownerId,id); }
    /** 显式重试失败分类。 */
    @PostMapping("/weekly-jobs/{id}/retry") public ResponseEntity<View> retry(@RequestParam @Min(1) long ownerId, @PathVariable UUID id, @Valid @RequestBody Retry request) {
        return ResponseEntity.accepted().body(service.retry(ownerId,id,request));
    }
    /** 获取报告历史游标页。 */
    @GetMapping("/weekly-reports") public History history(@RequestParam @Min(1) long ownerId,
            @RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) { return service.history(ownerId,cursor,limit); }
    /** 获取报告元数据。 */
    @GetMapping("/weekly-reports/{id}") public JsonNode report(@RequestParam @Min(1) long ownerId,@PathVariable UUID id) { return service.report(ownerId,id); }
    /** 获取单一分类列表。 */
    @GetMapping("/weekly-reports/{id}/categories/{category}") public JsonNode category(@RequestParam @Min(1) long ownerId,
            @PathVariable UUID id,@PathVariable Category category) { return service.category(ownerId,id,category); }
    /** 获取本人报告内一条新闻的全部详情。 */
    @GetMapping("/weekly-reports/{id}/articles/{event}") public JsonNode article(@RequestParam @Min(1) long ownerId,
            @PathVariable UUID id,@PathVariable @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String event) { return service.article(ownerId,id,event); }
}
