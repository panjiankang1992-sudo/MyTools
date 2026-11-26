package com.yuyutian.mytools.news.controller;

import com.yuyutian.mytools.news.Model.NewsModels.*;
import com.yuyutian.mytools.news.service.DigestEngine;
import com.yuyutian.mytools.news.service.EditionService;
import com.yuyutian.mytools.news.service.RegionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户代理接口，所有者只能由持有内部令牌的 Gateway 注入。 */
@Validated
@RestController
@RequestMapping("/internal/v1/news")
public class NewsController {
    private final EditionService service;
    private final RegionService regions;
    private final com.yuyutian.mytools.news.config.DeliveryProperties delivery;

    /** 创建日报控制器。 */
    public NewsController(EditionService service, RegionService regions, com.yuyutian.mytools.news.config.DeliveryProperties delivery) { this.service = service; this.regions = regions; this.delivery=delivery; }

    /** 返回真实已实现的能力与默认截止时刻。 */
    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(service.latestCutoff(), DigestEngine.RANKING_VERSION, 20, 10,
                "SOURCE_EXCERPT_V1", regions.statusAt(service.latestCutoff()), delivery.enabled());
    }

    /** 查询当前指定执行节点地区，不返回出口地址或指纹。 */
    @GetMapping("/local-region")
    public RegionSnapshot localRegion() { return regions.current(); }

    /** 同步生成有界预览，不提交任何发送任务。 */
    @PostMapping("/previews")
    public Edition preview(@RequestParam @Min(1) long ownerId, @Valid @RequestBody PreviewRequest request) {
        return service.preview(ownerId, request);
    }

    /** 查询本人的日报快照。 */
    @GetMapping("/editions/{id}")
    public Edition edition(@RequestParam @Min(1) long ownerId, @PathVariable UUID id) { return service.edition(ownerId, id); }

    /** 查询本人的最近历史。 */
    @GetMapping("/editions")
    public List<EditionHeader> history(@RequestParam @Min(1) long ownerId,
                                       @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return service.history(ownerId, limit);
    }

    /** 能力声明不把后续规划暴露为已实现功能。 */
    public record Capabilities(Instant latestCutoff, String rankingVersion, int headlineLimit,
                               int detailLimit, String detailMethod, String regionStatus, boolean deliveryEnabled) { }
}
