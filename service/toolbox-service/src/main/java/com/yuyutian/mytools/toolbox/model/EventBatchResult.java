package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 事件批量处理结果与计数汇总。
 *
 * @param results 逐条结果
 * @param accepted 接受数量
 * @param duplicate 重复数量
 * @param rejected 拒绝数量
 */
public record EventBatchResult(
        /** 逐条结果。 */
        @JsonProperty("results") List<EventItemResult> results,
        /** 接受数量。 */
        @JsonProperty("accepted") int accepted,
        /** 重复数量。 */
        @JsonProperty("duplicate") int duplicate,
        /** 拒绝数量。 */
        @JsonProperty("rejected") int rejected) {
}
