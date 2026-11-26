package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 所选周期与分类下的工具成功使用排名项。
 *
 * @param toolId 工具标识
 * @param success 成功使用次数
 */
public record StatsRankingEntry(
        /** 工具标识。 */
        @JsonProperty("toolId") String toolId,
        /** 成功使用次数。 */
        @JsonProperty("success") long success) {
}
