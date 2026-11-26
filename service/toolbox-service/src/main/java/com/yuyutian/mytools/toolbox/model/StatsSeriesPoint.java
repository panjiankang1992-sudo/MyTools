package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 按北京时间的自然日统计点；complete=false 时计数字段为 null。
 *
 * @param date 自然日
 * @param clicks 入口点击，未完整时为 null
 * @param success 成功使用，未完整时为 null
 * @param isToday 是否为当天
 * @param complete 该自然日聚合是否已知完整
 */
public record StatsSeriesPoint(
        /** 自然日。 */
        @JsonProperty("date") String date,
        /** 入口点击，未完整时为 null。 */
        @JsonProperty("clicks") Long clicks,
        /** 成功使用，未完整时为 null。 */
        @JsonProperty("success") Long success,
        /** 是否为当天。 */
        @JsonProperty("isToday") boolean isToday,
        /** 该自然日聚合是否已知完整。 */
        @JsonProperty("complete") boolean complete) {
}
