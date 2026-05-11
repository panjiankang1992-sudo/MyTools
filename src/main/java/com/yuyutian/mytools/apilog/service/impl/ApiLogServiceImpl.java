package com.yuyutian.mytools.apilog.service.impl;

import com.yuyutian.mytools.apilog.mapper.ApiLogMapper;
import com.yuyutian.mytools.apilog.model.ApiLog;
import com.yuyutian.mytools.apilog.model.ApiLogStatisticsResponse;
import com.yuyutian.mytools.apilog.service.ApiLogService;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * API日志服务实现类。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiLogServiceImpl implements ApiLogService {

    private final ApiLogMapper apiLogMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    @Async
    public void saveLog(ApiLog apiLog) {
        try {
            // 使用雪花算法生成ID
            if (apiLog.getId() == null) {
                apiLog.setId(snowflakeIdGenerator.nextId());
            }
            apiLogMapper.insert(apiLog);
            log.debug("API日志记录成功: {} {}", apiLog.getMethod(), apiLog.getApiPath());
        } catch (Exception e) {
            // 异步记录日志，失败不影響主流程
            log.error("API日志记录失败: {} {}", apiLog.getMethod(), apiLog.getApiPath(), e);
        }
    }

    @Override
    public ApiLogStatisticsResponse getStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        // 统计总请求量
        long totalRequests = apiLogMapper.countByTimeRange(startTime, endTime);

        // 统计成功请求量
        long successRequests = apiLogMapper.countByTimeRangeAndSuccess(startTime, endTime, true);

        // 统计失败请求量
        long failedRequests = apiLogMapper.countByTimeRangeAndSuccess(startTime, endTime, false);

        // 统计平均响应时间
        Long avgDuration = apiLogMapper.avgDurationByTimeRange(startTime, endTime);
        long avgDurationMs = avgDuration != null ? avgDuration : 0;

        // 按小时统计
        List<ApiLogMapper.HourlyStat> hourlyStats = apiLogMapper.countHourly(startTime, endTime);
        List<ApiLogStatisticsResponse.HourlyStats> hourlyStatsList = hourlyStats.stream()
                .map(stat -> new ApiLogStatisticsResponse.HourlyStats(
                        stat.getHour(),
                        stat.getCount() != null ? stat.getCount() : 0,
                        stat.getSuccessCount() != null ? stat.getSuccessCount() : 0
                ))
                .collect(Collectors.toList());

        // 按模块统计
        List<ApiLogMapper.ModuleStat> moduleStats = apiLogMapper.countByModule(startTime, endTime);
        List<ApiLogStatisticsResponse.ModuleStats> moduleStatsList = moduleStats.stream()
                .map(stat -> {
                    long count = stat.getCount() != null ? stat.getCount() : 0;
                    double percentage = totalRequests > 0 ? (count * 100.0 / totalRequests) : 0;
                    return new ApiLogStatisticsResponse.ModuleStats(
                            stat.getModule() != null ? stat.getModule() : "OTHER",
                            count,
                            Math.round(percentage * 100.0) / 100.0
                    );
                })
                .collect(Collectors.toList());

        return new ApiLogStatisticsResponse(
                totalRequests,
                successRequests,
                failedRequests,
                avgDurationMs,
                hourlyStatsList,
                moduleStatsList
        );
    }
}
