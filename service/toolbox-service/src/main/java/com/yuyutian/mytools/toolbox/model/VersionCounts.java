package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 指定版本的单元、课文与条目数量。
 *
 * @param units 单元数量
 * @param lessons 课文数量
 * @param entries 条目数量
 */
public record VersionCounts(
        /** 单元数量。 */
        @JsonProperty("units") int units,
        /** 课文数量。 */
        @JsonProperty("lessons") int lessons,
        /** 条目数量。 */
        @JsonProperty("entries") int entries) {
}
