package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 教材条目，字段与实施契约 3.1 节一一对应。
 * context 仅用于校对用途，发布内容不得在 context 中保留待填答案。
 *
 * @param entryId 稳定条目标识
 * @param entryVersion 条目版本
 * @param editionId 所属教材版本标识
 * @param unitId 所属单元标识
 * @param lessonId 所属课文标识，可为空
 * @param order 条目顺序
 * @param form 条目形态
 * @param listTypes 原教材清单类别
 * @param text 条目正文
 * @param pinyinDisplay 展示用拼音，可为空
 * @param pinyinSegments 逐字拼音对齐片段
 * @param meaning 义项，可为空
 * @param partOfSpeech 词性，可为空
 * @param senseId 义项标识
 * @param context 校对语境，可为空
 * @param promptContext 可展示的提示语境，可为空
 * @param acceptableAnswers 可接受答案
 * @param sourceRefs 来源定位
 * @param reviewStatus 校对状态
 * @param blockingIssues 阻断发布的校对问题
 */
public record CurriculumEntry(
        @JsonProperty("entryId") String entryId,
        @JsonProperty("entryVersion") String entryVersion,
        @JsonProperty("editionId") String editionId,
        @JsonProperty("unitId") String unitId,
        @JsonProperty("lessonId") String lessonId,
        @JsonProperty("order") int order,
        @JsonProperty("form") EntryForm form,
        @JsonProperty("listTypes") List<ListType> listTypes,
        @JsonProperty("text") String text,
        @JsonProperty("pinyinDisplay") String pinyinDisplay,
        @JsonProperty("pinyinSegments") List<PinyinSegment> pinyinSegments,
        @JsonProperty("meaning") String meaning,
        @JsonProperty("partOfSpeech") String partOfSpeech,
        @JsonProperty("senseId") String senseId,
        @JsonProperty("context") String context,
        @JsonProperty("promptContext") String promptContext,
        @JsonProperty("acceptableAnswers") List<String> acceptableAnswers,
        @JsonProperty("sourceRefs") List<EntrySourceRef> sourceRefs,
        @JsonProperty("reviewStatus") ReviewStatus reviewStatus,
        @JsonProperty("blockingIssues") List<String> blockingIssues) {
}
