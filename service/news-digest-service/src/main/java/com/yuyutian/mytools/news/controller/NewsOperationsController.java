package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.mapper.RetentionRepository;
import com.yuyutian.mytools.news.service.NewsOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Map;

/** 保留策略和运行状态仅允许独立管理身份访问。 */
@RestController
@RequestMapping("/internal/admin")
public class NewsOperationsController {
    private final RetentionRepository retention;
    private final NewsOperations operations;
    private final Clock clock;
    /** 注入有界清理、聚合指标和统一时钟。 */
    public NewsOperationsController(RetentionRepository retention,NewsOperations operations,Clock clock){this.retention=retention;this.operations=operations;this.clock=clock;}
    /** 管理响应禁止缓存。 */
    @ModelAttribute
    public void privateResponse(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store, private");}
    /** 获取聚合运行状态、保留积压及最后一次清理检查点。 */
    @GetMapping("/operations")
    public Map<String,Object> status(){return operations.status();}
    /** 预览到期数量，不改变数据。 */
    @GetMapping("/retention/preview")
    public Map<String,Long> preview(){return retention.preview(clock.instant());}
    /** 手动执行一个有界批次，仍需显式启用保留功能。 */
    @PostMapping("/retention/sweep")
    public Map<String,Integer> sweep(){return retention.sweep(clock.instant());}
    /** 配置来源条款要求的更短正文保留期。 */
    @PutMapping("/sources/{id}/retention")
    public Map<String,Integer> sourcePolicy(@PathVariable String id,@Valid @RequestBody SourcePolicy request){retention.sourcePolicy(id,request.evidenceHours(),clock.instant());return Map.of("evidenceHours",request.evidenceHours());}
    /** 来源保留策略只能缩短默认上限。 */
    public record SourcePolicy(@Min(1) @Max(168) int evidenceHours){}
}
