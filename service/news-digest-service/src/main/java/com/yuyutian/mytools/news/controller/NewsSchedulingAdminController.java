package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.mapper.SchedulingRepository;
import com.yuyutian.mytools.news.service.SchedulingService;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** 新闻编排运维入口仅使用管理凭据，不经 Gateway 暴露。 */
@RestController
@RequestMapping("/internal/admin/news/scheduling")
public class NewsSchedulingAdminController {
    private final SchedulingService service;
    private final SchedulingRepository repository;
    private final Clock clock;
    /** 创建有界运维入口。 */
    public NewsSchedulingAdminController(SchedulingService service, SchedulingRepository repository, Clock clock) {
        this.service = service; this.repository = repository; this.clock = clock;
    }
    /** 手动执行一次到期扫描，遵守自动调度开关与原有时间窗口。 */
    @PostMapping("/scan")
    public Map<String, Integer> scan() { return Map.of("created", service.scan()); }
    /** 手动执行一次有界对账。 */
    @PostMapping("/relay")
    public Map<String, Integer> relay() { return Map.of("processed", service.relay()); }
    /** 查看失败原因和下一次对账时间。 */
    @GetMapping("/runs/{id}/outbox")
    public Map<String, Object> outbox(@PathVariable UUID id) { return repository.outbox(id); }
    /** 配置修复后恢复同一个运行，不重置尝试次数或更换任务身份。 */
    @PostMapping("/runs/{id}/redrive")
    public Map<String, Object> redrive(@PathVariable UUID id) { repository.redrive(id, clock.instant()); return repository.outbox(id); }
}
