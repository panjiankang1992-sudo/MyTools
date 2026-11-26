package com.yuyutian.mytools.toolbox.model;

import java.time.LocalDate;

/**
 * 日聚合查询结果行。
 *
 * @param localDate 北京时间自然日
 * @param eventType 事件类型
 * @param count 累计计数
 */
public record DailyStatBucket(
        /** 北京时间自然日。 */
        LocalDate localDate,
        /** 事件类型。 */
        String eventType,
        /** 累计计数。 */
        long count) {
}
