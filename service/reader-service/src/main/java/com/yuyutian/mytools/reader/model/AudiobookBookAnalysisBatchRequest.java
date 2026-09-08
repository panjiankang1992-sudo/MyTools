package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 执行器回写整书人物、关系和说话人归因的受限结果。
 *
 * @param analysisFingerprintSha256 分析结果规范化摘要
 * @param analysisModelVersion 已执行的分析模型版本标识
 * @param analysisRuleVersion 已执行的候选、归并和范围校验规则版本标识
 * @param characters 归并后的角色清单
 * @param relationships 角色关系清单
 * @param speechSegments 说话人归因片段
 */
public record AudiobookBookAnalysisBatchRequest(
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String analysisFingerprintSha256,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") String analysisModelVersion,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}") String analysisRuleVersion,
        @NotNull @Size(max = 5000) List<@Valid Character> characters,
        @NotNull @Size(max = 20000) List<@Valid Relationship> relationships,
        @NotNull @Size(max = 200000) List<@Valid SpeechSegment> speechSegments) {

    /**
     * 一条全书归并后的角色档案。
     *
     * @param canonicalName 稳定角色名称
     * @param displayName 展示名称
     * @param presentation 性别或声音呈现类型
     * @param characterType 人物类型
     * @param traits 人物特点标签
     * @param firstChapterIndex 首次出现章节
     * @param occurrenceCount 出现次数估计
     * @param confidence 分析置信度
     * @param aliases 别名及证据
     */
    public record Character(
            @NotBlank @Size(max = 256) String canonicalName,
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank @Pattern(regexp = "FEMININE|MASCULINE|NON_BINARY|NEUTRAL|UNKNOWN") String presentation,
            @NotBlank @Size(max = 64) String characterType,
            @NotNull @Size(max = 32) List<@NotBlank @Size(max = 80) String> traits,
            @PositiveOrZero int firstChapterIndex,
            @Positive int occurrenceCount,
            @DecimalMin("0.0000") @DecimalMax("1.0000") double confidence,
            @NotNull @Size(max = 128) List<@Valid Alias> aliases) {
    }

    /**
     * 一条人物别名与可回溯证据。
     *
     * @param alias 别名
     * @param aliasType 别名类型
     * @param evidenceChapterIndex 证据章节，可为空
     * @param evidenceStartCodepoint 证据起始码点，可为空
     * @param evidenceEndCodepoint 证据结束码点，可为空
     * @param confidence 分析置信度
     */
    public record Alias(
            @NotBlank @Size(max = 256) String alias,
            @NotBlank @Size(max = 32) String aliasType,
            @PositiveOrZero Integer evidenceChapterIndex,
            @PositiveOrZero Integer evidenceStartCodepoint,
            @PositiveOrZero Integer evidenceEndCodepoint,
            @DecimalMin("0.0000") @DecimalMax("1.0000") double confidence) {
    }

    /**
     * 一条有方向的角色关系。
     *
     * @param sourceCanonicalName 源角色稳定名称
     * @param targetCanonicalName 目标角色稳定名称
     * @param relationshipType 关系类型
     * @param direction 方向语义
     * @param evidenceChapterIndex 证据章节，可为空
     * @param evidenceStartCodepoint 证据起始码点，可为空
     * @param evidenceEndCodepoint 证据结束码点，可为空
     * @param confidence 分析置信度
     */
    public record Relationship(
            @NotBlank @Size(max = 256) String sourceCanonicalName,
            @NotBlank @Size(max = 256) String targetCanonicalName,
            @NotBlank @Size(max = 64) String relationshipType,
            @NotBlank @Size(max = 32) String direction,
            @PositiveOrZero Integer evidenceChapterIndex,
            @PositiveOrZero Integer evidenceStartCodepoint,
            @PositiveOrZero Integer evidenceEndCodepoint,
            @DecimalMin("0.0000") @DecimalMax("1.0000") double confidence) {
    }

    /**
     * 一条章节中可用于后续分角色合成的说话人片段。
     *
     * @param chapterIndex 章节序号
     * @param sequenceNumber 章节内稳定序号
     * @param textStartCodepoint 冻结正文中的起始 Unicode 码点偏移
     * @param textEndCodepoint 冻结正文中的结束 Unicode 码点偏移
     * @param speakerKind 说话人类别
     * @param speakerCanonicalName 角色稳定名称，旁白或未知时为空
     * @param deliveryTags 情绪和语速等演绎标签
     * @param confidence 分析置信度
     * @param evidenceExcerpt 低置信度片段从冻结正文派生的受限审核摘录，可为空
     */
    public record SpeechSegment(
            @PositiveOrZero int chapterIndex,
            @PositiveOrZero int sequenceNumber,
            @PositiveOrZero int textStartCodepoint,
            @Positive int textEndCodepoint,
            @NotBlank @Pattern(regexp = "CHARACTER|NARRATOR|UNKNOWN") String speakerKind,
            @Size(max = 256) String speakerCanonicalName,
            @NotNull @Size(max = 16) List<@NotBlank @Size(max = 64) String> deliveryTags,
            @DecimalMin("0.0000") @DecimalMax("1.0000") double confidence,
            @Size(max = 768) String evidenceExcerpt) {
    }
}
