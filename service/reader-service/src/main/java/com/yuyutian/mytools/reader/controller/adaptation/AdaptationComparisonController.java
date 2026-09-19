package com.yuyutian.mytools.reader.controller.adaptation;

import com.yuyutian.mytools.reader.model.adaptation.AdaptationComparison;
import com.yuyutian.mytools.reader.service.adaptation.AdaptationComparisonService;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 受既有服务令牌保护的历史比较路由，不接收原文或任意比较对象。 */
@Validated
@RestController
@RequestMapping("/api/v1/reader-state/chapter-adaptations/{adaptationId}")
public class AdaptationComparisonController {
    private final AdaptationComparisonService service;

    /** 注入只读冻结版本比较。 */
    public AdaptationComparisonController(AdaptationComparisonService service) {
        this.service = service;
    }

    /** 返回有界段落差异或双栏降级视图。 */
    @GetMapping("/comparison")
    public AdaptationComparison compare(@RequestParam @Positive long ownerId, @PathVariable UUID adaptationId) {
        return service.compare(ownerId, adaptationId);
    }
}
