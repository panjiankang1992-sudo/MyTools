package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 教材正文文件 book.json 的顶层结构。
 *
 * @param edition 教材版本元数据
 * @param units 单元列表
 * @param lessons 课文列表
 * @param entries 条目列表
 */
// 内容文件允许携带额外说明字段，接口请求体仍保持严格校验。
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishedBook(
        /** 教材版本元数据。 */
        @JsonProperty("edition") TextbookEdition edition,
        /** 单元列表。 */
        @JsonProperty("units") List<CurriculumUnit> units,
        /** 课文列表。 */
        @JsonProperty("lessons") List<CurriculumLesson> lessons,
        /** 条目列表。 */
        @JsonProperty("entries") List<CurriculumEntry> entries) {
}
