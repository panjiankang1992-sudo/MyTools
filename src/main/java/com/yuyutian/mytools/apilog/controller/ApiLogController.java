package com.yuyutian.mytools.apilog.controller;

import com.yuyutian.mytools.apilog.model.ApiLogStatisticsResponse;
import com.yuyutian.mytools.apilog.service.ApiLogService;
import com.yuyutian.mytools.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * API日志控制器。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class ApiLogController {

    private final ApiLogService apiLogService;

    /**
     * 获取API日志统计信息。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 统计响应
     */
    @GetMapping("/statistics")
    public Result<ApiLogStatisticsResponse> getStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        // 默认查询最近24小时
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }
        if (startTime == null) {
            startTime = endTime.minusHours(24);
        }

        log.info("查询API日志统计: {} ~ {}", startTime, endTime);

        ApiLogStatisticsResponse statistics = apiLogService.getStatistics(startTime, endTime);
        return Result.success(statistics);
    }
}
