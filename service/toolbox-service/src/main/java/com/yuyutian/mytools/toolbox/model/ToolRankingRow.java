package com.yuyutian.mytools.toolbox.model;

/**
 * 工具成功使用排名行。
 *
 * @param toolId 工具标识
 * @param success 成功使用次数
 */
public record ToolRankingRow(
        /** 工具标识。 */
        String toolId,
        /** 成功使用次数。 */
        long success) {
}
