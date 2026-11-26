package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 条目分页响应；total 等于按同一过滤条件全量翻页取得的数量。
 *
 * @param entries 当前页条目
 * @param nextCursor 下一页游标，可为空
 * @param total 过滤后的条目总数
 * @param contentVersion 内容版本
 */
public record TextbookEntriesPage(
        /** 当前页条目。 */
        @JsonProperty("entries") List<CurriculumEntry> entries,
        /** 下一页游标，可为空。 */
        @JsonProperty("nextCursor") String nextCursor,
        /** 过滤后的条目总数。 */
        @JsonProperty("total") long total,
        /** 内容版本。 */
        @JsonProperty("contentVersion") String contentVersion) {
}
