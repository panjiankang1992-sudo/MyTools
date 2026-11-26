package com.yuyutian.mytools.toolbox.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时间工具：统一 UTC 传输与北京时间自然日口径，禁止依赖 JVM 默认时区。
 */
public final class ToolboxTime {

    /** 统计自然日固定使用北京时间。 */
    public static final ZoneId STAT_ZONE = ZoneId.of("Asia/Shanghai");

    /** 契约要求的统计时区名称。 */
    public static final String STAT_ZONE_ID = "Asia/Shanghai";

    private ToolboxTime() {
    }

    /**
     * 把时间点转换为北京时间自然日。
     *
     * @param instant 时间点
     * @return 北京时间自然日
     */
    public static LocalDate statDate(Instant instant) {
        return instant.atZone(STAT_ZONE).toLocalDate();
    }

    /**
     * 返回指定北京自然日的起始时间点。
     *
     * @param localDate 北京自然日
     * @return 该自然日 00:00 对应的 UTC 时间点
     */
    public static Instant startOfDay(LocalDate localDate) {
        return localDate.atStartOfDay(STAT_ZONE).toInstant();
    }

    /**
     * 返回指定北京自然日的次日起始时间点。
     *
     * @param localDate 北京自然日
     * @return 次日 00:00 对应的 UTC 时间点
     */
    public static Instant endOfDayExclusive(LocalDate localDate) {
        return localDate.plusDays(1).atStartOfDay(STAT_ZONE).toInstant();
    }

    /**
     * 以带 Z 的 ISO 8601 形式输出时间点。
     *
     * @param instant 时间点，可为空
     * @return ISO 8601 字符串或空
     */
    public static String isoOrNull(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC).toString().replace("+00:00", "Z");
    }
}
