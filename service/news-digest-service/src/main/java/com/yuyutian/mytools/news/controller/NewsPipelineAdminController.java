package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.PipelineModels.*;
import com.yuyutian.mytools.news.mapper.*;
import com.yuyutian.mytools.news.service.*;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.*;

/** 周期采集运维入口只接受管理令牌，普通用户不能手动构造地区任务。 */
@RestController
@RequestMapping("/internal/admin/news/pipeline")
public class NewsPipelineAdminController {
    private final SourcePollingService sources;
    private final SourcePollingRepository polls;
    private final RegionJobService regions;
    private final RegionJobRepository jobs;
    private final Clock clock;
    /** 创建脱敏运维接口。 */
    public NewsPipelineAdminController(SourcePollingService sources, SourcePollingRepository polls, RegionJobService regions, RegionJobRepository jobs, Clock clock) {
        this.sources = sources; this.polls = polls; this.regions = regions; this.jobs = jobs; this.clock = clock;
    }
    /** 查看来源的采样计划和退避。 */
    @GetMapping("/sources")
    public List<PollState> sources() { return polls.states(); }
    /** 触发一次有界采样。 */
    @PostMapping("/poll")
    public Map<String, Integer> poll() { return Map.of("processed", sources.poll()); }
    /** 查看地区任务状态，不暴露报告指纹。 */
    @GetMapping("/region-jobs")
    public List<RegionJob> jobs() { return jobs.jobs(); }
    /** 扫描当前地区桶。 */
    @PostMapping("/region/scan")
    public Map<String, Object> scan() { return Map.of("created", regions.scan().isPresent()); }
    /** 有界执行地区队列。 */
    @PostMapping("/region/relay")
    public Map<String, Integer> relay() { return Map.of("processed", regions.relay()); }
    /** 取消地区任务，远端状态由原队列继续对账。 */
    @PostMapping("/region-jobs/{id}/cancel")
    public RegionJob cancel(@PathVariable UUID id) { return jobs.cancel(id, clock.instant()); }
    /** 修复配置后恢复原死信任务。 */
    @PostMapping("/region-jobs/{id}/redrive")
    public RegionJob redrive(@PathVariable UUID id) { jobs.redrive(id, clock.instant()); return jobs.job(id); }
}
