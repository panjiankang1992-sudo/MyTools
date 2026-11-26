package com.yuyutian.mytools.toolbox.controller.stats;

import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.config.ToolboxProperties;
import com.yuyutian.mytools.toolbox.model.StatsResponse;
import com.yuyutian.mytools.toolbox.model.ToolCategory;
import com.yuyutian.mytools.toolbox.service.stats.StatsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计读取接口：统计开关关闭时返回 TOOLBOX_008，绝不回落演示数据。
 */
@RestController
@RequestMapping("/public/v1/stats")
public class StatsController {

    /** 全部分类取值。 */
    private static final String CATEGORY_ALL = "all";

    private final StatsService statsService;
    private final ToolboxProperties properties;

    /**
     * 构造控制器。
     *
     * @param statsService 统计读取服务
     * @param properties   门面配置
     */
    public StatsController(StatsService statsService, ToolboxProperties properties) {
        this.statsService = statsService;
        this.properties = properties;
    }

    /**
     * 返回聚合统计。
     *
     * @param days     统计窗口天数，只允许 7 或 30
     * @param category 工具分类，只允许 all/pdf/copybook/words
     * @return 统计响应信封
     */
    @GetMapping
    public ApiResponse<StatsResponse> stats(@RequestParam(defaultValue = "7") int days,
                                            @RequestParam(defaultValue = CATEGORY_ALL) String category) {
        if (!properties.statsEnabled()) {
            // 统计不可用时明确失败，让页面显示诚实的不可用状态。
            throw new BusinessException(ErrorCode.TOOLBOX_008, Map.of("reason", "statsDisabled"));
        }
        String normalized = category == null ? CATEGORY_ALL : category.trim();
        String categoryFilter;
        if (CATEGORY_ALL.equals(normalized)) {
            categoryFilter = null;
        } else {
            categoryFilter = ToolCategory.parse(normalized)
                    .orElseThrow(() -> BusinessException.invalid("category", "unsupportedValue"))
                    .value();
        }
        return ApiResponse.ok(statsService.read(days, categoryFilter));
    }
}
