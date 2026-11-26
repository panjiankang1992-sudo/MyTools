package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 指定不可变版本的完整结构与计数。
 *
 * @param edition 教材版本元数据
 * @param units 单元列表
 * @param lessons 课文列表
 * @param listTypes 该版本出现的清单类别
 * @param entryCount 条目总数
 * @param counts 结构计数
 * @param sha256 正文文件 SHA-256
 */
public record TextbookVersion(
        /** 教材版本元数据。 */
        @JsonProperty("edition") TextbookEdition edition,
        /** 单元列表。 */
        @JsonProperty("units") List<CurriculumUnit> units,
        /** 课文列表。 */
        @JsonProperty("lessons") List<CurriculumLesson> lessons,
        /** 该版本出现的清单类别。 */
        @JsonProperty("listTypes") List<ListType> listTypes,
        /** 条目总数。 */
        @JsonProperty("entryCount") int entryCount,
        /** 结构计数。 */
        @JsonProperty("counts") VersionCounts counts,
        /** 正文文件 SHA-256。 */
        @JsonProperty("sha256") String sha256) {
}
