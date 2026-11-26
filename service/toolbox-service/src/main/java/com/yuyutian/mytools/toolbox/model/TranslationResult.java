package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 按输入顺序返回翻译结果，失败项没有伪造的英文或释义。
 *
 * @param items 翻译结果
 */
public record TranslationResult(List<Item> items) {

    /**
     * 一项翻译结果。
     *
     * @param id 原条目标识
     * @param sourceText 未改动的来源正文
     * @param englishText 英文正文，失败时为空
     * @param meaning 中文释义，失败时为空
     * @param status translated 或 failed
     * @param pinyinSegments 教材中的逐字拼音，仅语文命中时提供
     */
    public record Item(String id, String sourceText, String englishText, String meaning, String status,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<PinyinSegment> pinyinSegments) {

        /**
         * 兼容英语翻译响应，省略新增的拼音字段。
         *
         * @param id 原条目标识
         * @param sourceText 来源正文
         * @param englishText 英文正文
         * @param meaning 中文释义
         * @param status 结果状态
         */
        public Item(String id, String sourceText, String englishText, String meaning, String status) {
            this(id, sourceText, englishText, meaning, status, null);
        }
    }
}
