package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 教材单元；同一本书内按 order 与 unitId 稳定排序。
 *
 * @param unitId 稳定单元标识
 * @param editionId 所属教材版本标识
 * @param order 单元顺序
 * @param title 单元标题
 */
public record CurriculumUnit(
        /** 稳定单元标识。 */
        @JsonProperty("unitId") String unitId,
        /** 所属教材版本标识。 */
        @JsonProperty("editionId") String editionId,
        /** 单元顺序。 */
        @JsonProperty("order") int order,
        /** 单元标题。 */
        @JsonProperty("title") String title) {
}
