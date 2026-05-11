package com.yuyutian.mytools.apilog.service;

import com.yuyutian.mytools.apilog.model.ApiLog;
import com.yuyutian.mytools.apilog.model.ApiLogStatisticsResponse;

import java.time.LocalDateTime;

/**
 * API日志服务接口。
 *
 * @author mytools
 * @since 2026-05-11
 */
public interface ApiLogService {

    /**
     * 记录API日志。
     *
     * @param log 日志信息
     */
    void saveLog(ApiLog log);

    /**
     * 获取统计信息。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 统计响应
     */
    ApiLogStatisticsResponse getStatistics(LocalDateTime startTime, LocalDateTime endTime);
}
