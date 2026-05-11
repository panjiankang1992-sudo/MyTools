package com.yuyutian.mytools.apilog.mapper;

import com.yuyutian.mytools.apilog.model.ApiLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API日志数据访问层。
 *
 * @author mytools
 * @since 2026-05-11
 */
@Mapper
public interface ApiLogMapper {

    /**
     * 插入日志。
     *
     * @param log 日志对象
     * @return 影响行数
     */
    int insert(ApiLog log);

    /**
     * 按时间范围统计请求量。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 请求量
     */
    long countByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 按时间范围和成功状态统计请求量。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param success 是否成功
     * @return 请求量
     */
    long countByTimeRangeAndSuccess(@Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime,
                                   @Param("success") Boolean success);

    /**
     * 按时间范围统计平均响应时间。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 平均响应时间
     */
    Long avgDurationByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 按小时统计请求量。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 统计结果列表
     */
    List<HourlyStat> countHourly(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 按模块统计请求量。
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 统计结果列表
     */
    List<ModuleStat> countByModule(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * 按小时统计结果。
     */
    interface HourlyStat {
        String getHour();
        Long getCount();
        Long getSuccessCount();
    }

    /**
     * 按模块统计结果。
     */
    interface ModuleStat {
        String getModule();
        Long getCount();
    }
}
