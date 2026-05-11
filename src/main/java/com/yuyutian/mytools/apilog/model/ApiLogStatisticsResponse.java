package com.yuyutian.mytools.apilog.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * API日志统计响应。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiLogStatisticsResponse {

    /** 总请求量 */
    private long totalRequests;

    /** 成功请求量 */
    private long successRequests;

    /** 失败请求量 */
    private long failedRequests;

    /** 平均响应时间（毫秒） */
    private long avgDurationMs;

    /** 按小时统计的请求量 */
    private List<HourlyStats> hourlyStats;

    /** 按模块统计的请求量 */
    private List<ModuleStats> moduleStats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyStats {
        private String hour;
        private long count;
        private long successCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleStats {
        private String module;
        private long count;
        private double percentage;
    }
}
