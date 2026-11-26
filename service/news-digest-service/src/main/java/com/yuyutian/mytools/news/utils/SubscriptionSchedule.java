package com.yuyutian.mytools.news.utils;

import java.time.*;

/** 按业务当地日期计算每日截止，夏令时重叠取较早偏移，缺失时刻向后移动。 */
public final class SubscriptionSchedule {
    private SubscriptionSchedule() { }

    /** 将当地日期与分钟级时刻转换为唯一截止时刻。 */
    public static Instant cutoff(LocalDate date, String time, String zone) {
        return date.atTime(LocalTime.parse(time)).atZone(ZoneId.of(zone)).toInstant();
    }

    /** 新启用或修改的订阅只从之后的截止开始，不能追溯产生旧任务。 */
    public static LocalDate firstDate(Instant now, String time, String zone) {
        LocalDate date = now.atZone(ZoneId.of(zone)).toLocalDate();
        return cutoff(date, time, zone).isBefore(now) ? date.plusDays(1) : date;
    }

    /** 停机恢复时只考虑最近一个已到截止的业务日期。 */
    public static LocalDate latestDate(Instant now, String time, String zone) {
        LocalDate date = now.atZone(ZoneId.of(zone)).toLocalDate();
        return cutoff(date, time, zone).isAfter(now) ? date.minusDays(1) : date;
    }
}
