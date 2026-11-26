package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统计总量；所有计数为非负长整数，序列化为 JSON 数字。
 *
 * @param todayClicks 今日入口点击
 * @param todaySuccess 今日成功使用
 * @param allTimeSuccess 累计成功使用
 * @param todayPrintRequests 今日打印发起
 */
public record StatsTotals(
        /** 今日入口点击。 */
        @JsonProperty("todayClicks") long todayClicks,
        /** 今日成功使用。 */
        @JsonProperty("todaySuccess") long todaySuccess,
        /** 累计成功使用。 */
        @JsonProperty("allTimeSuccess") long allTimeSuccess,
        /** 今日打印发起。 */
        @JsonProperty("todayPrintRequests") long todayPrintRequests) {
}
