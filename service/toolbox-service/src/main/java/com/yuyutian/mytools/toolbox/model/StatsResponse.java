package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 统计读取响应，字段与契约 6.3 节一致。
 *
 * @param timezone 统计时区
 * @param generatedAt 生成时间
 * @param completeThrough 聚合完整水位，可为空
 * @param partial 明细聚合是否尚未追平
 * @param today 北京时间当天日期
 * @param totals 总量
 * @param series 连续自然日序列
 * @param ranking 工具排名
 */
public record StatsResponse(
        /** 统计时区。 */
        @JsonProperty("timezone") String timezone,
        /** 生成时间。 */
        @JsonProperty("generatedAt") String generatedAt,
        /** 聚合完整水位，可为空。 */
        @JsonProperty("completeThrough") String completeThrough,
        /** 明细聚合是否尚未追平。 */
        @JsonProperty("partial") boolean partial,
        /** 北京时间当天日期。 */
        @JsonProperty("today") String today,
        /** 总量。 */
        @JsonProperty("totals") StatsTotals totals,
        /** 连续自然日序列。 */
        @JsonProperty("series") List<StatsSeriesPoint> series,
        /** 工具排名。 */
        @JsonProperty("ranking") List<StatsRankingEntry> ranking) {
}
