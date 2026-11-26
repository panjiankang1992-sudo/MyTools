package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 逐字拼音对齐片段，显式对齐不做空格硬拆。
 *
 * @param grapheme 字素或码点序列
 * @param syllable 对应读音
 */
public record PinyinSegment(
        /** 字素或码点序列。 */
        @JsonProperty("grapheme") String grapheme,
        /** 对应读音。 */
        @JsonProperty("syllable") String syllable) {
}
