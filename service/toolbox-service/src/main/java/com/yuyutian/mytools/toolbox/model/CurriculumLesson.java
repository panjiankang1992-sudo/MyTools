package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 教材课文；属于唯一单元，按 order 与 lessonId 稳定排序。
 *
 * @param lessonId 稳定课文标识
 * @param unitId 所属单元标识
 * @param order 课文顺序
 * @param title 课文标题
 */
public record CurriculumLesson(
        /** 稳定课文标识。 */
        @JsonProperty("lessonId") String lessonId,
        /** 所属单元标识。 */
        @JsonProperty("unitId") String unitId,
        /** 课文顺序。 */
        @JsonProperty("order") int order,
        /** 课文标题。 */
        @JsonProperty("title") String title) {
}
