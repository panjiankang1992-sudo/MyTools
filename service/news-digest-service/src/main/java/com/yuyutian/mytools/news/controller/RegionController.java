package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.RegionModels.*;
import com.yuyutian.mytools.news.mapper.RegionRepository;
import com.yuyutian.mytools.news.service.RegionService;
import jakarta.validation.Valid;
import com.yuyutian.mytools.news.config.PipelineProperties;
import com.yuyutian.mytools.news.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 节点报告与来源管理使用不同鉴权路径，均不接受普通用户写入。 */
@RestController
public class RegionController {
    private final RegionService service;
    private final RegionRepository repository;
    private final PipelineProperties pipeline;

    /** 创建地区管理控制器。 */
    public RegionController(RegionService service, RegionRepository repository, PipelineProperties pipeline) { this.service = service; this.repository = repository; this.pipeline = pipeline; }

    /** 接收指定节点去标识的报告。 */
    @PostMapping("/internal/worker/news/region-observations")
    public ObservationReceipt observe(@Valid @RequestBody RegionObservation report) {
        // 自动链路开启后关闭旧静态令牌上报，避免绕过任务租约校验。
        if (pipeline.enabled()) throw new NewsException(ErrorCode.NEWS_016);
        return service.observe(report);
    }

    /** 登记不可变来源包版本。 */
    @PostMapping("/internal/admin/news/region-source-packs")
    public RegionSourcePack register(@Valid @RequestBody RegionSourcePack pack) { return service.registerPack(pack); }

    /** 列出地区来源包供管理员核验。 */
    @GetMapping("/internal/admin/news/region-source-packs")
    public List<RegionSourcePack> packs() { return repository.packs(); }
}
