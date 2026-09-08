package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 可供审核和后续音色规划读取的全书结构化分析视图。
 *
 * @param generationId 生成运行标识
 * @param analysisModelVersion 已执行的分析模型版本；历史记录使用兼容占位值
 * @param analysisRuleVersion 已执行的分析规则版本；历史记录使用兼容占位值
 * @param characters 已归并角色档案
 * @param relationships 已归并关系
 * @param speechSegments 已归因说话人片段
 */
public record AudiobookBookAnalysisView(UUID generationId, String analysisModelVersion, String analysisRuleVersion,
                                        List<Character> characters, List<Relationship> relationships,
                                        List<SpeechSegment> speechSegments) {

    /**
     * 创建兼容旧版本生成记录的分析视图。
     *
     * @param generationId 生成运行标识
     * @param characters 已归并角色档案
     * @param relationships 已归并关系
     * @param speechSegments 已归因说话人片段
     */
    public AudiobookBookAnalysisView(UUID generationId, List<Character> characters,
                                     List<Relationship> relationships, List<SpeechSegment> speechSegments) {
        this(generationId, "UNKNOWN_LEGACY", "UNKNOWN_LEGACY", characters, relationships, speechSegments);
    }

    /**
     * 一条角色档案。
     *
     * @param canonicalName 稳定角色名称
     * @param displayName 展示名称
     * @param presentation 性别或声音呈现类型
     * @param characterType 人物类型
     * @param traits 人物特点
     * @param firstChapterIndex 首次出现章节
     * @param occurrenceCount 出现次数估计
     * @param confidence 分析置信度
     * @param reviewStatus 审核状态
     * @param aliases 别名证据
     */
    public record Character(String canonicalName, String displayName, String presentation, String characterType,
                            List<String> traits, int firstChapterIndex, int occurrenceCount, double confidence,
                            String reviewStatus, List<Alias> aliases) {
    }

    /**
     * 一条角色别名证据。
     *
     * @param alias 别名
     * @param aliasType 别名类型
     * @param evidenceChapterIndex 证据章节
     * @param evidenceStartCodepoint 证据起始码点
     * @param evidenceEndCodepoint 证据结束码点
     * @param confidence 分析置信度
     */
    public record Alias(String alias, String aliasType, Integer evidenceChapterIndex, Integer evidenceStartCodepoint,
                        Integer evidenceEndCodepoint, double confidence) {
    }

    /**
     * 一条有方向的角色关系。
     *
     * @param sourceCanonicalName 源角色稳定名称
     * @param targetCanonicalName 目标角色稳定名称
     * @param relationshipType 关系类型
     * @param direction 方向语义
     * @param evidenceChapterIndex 证据章节
     * @param evidenceStartCodepoint 证据起始码点
     * @param evidenceEndCodepoint 证据结束码点
     * @param confidence 分析置信度
     * @param reviewStatus 审核状态
     */
    public record Relationship(String sourceCanonicalName, String targetCanonicalName, String relationshipType,
                               String direction, Integer evidenceChapterIndex, Integer evidenceStartCodepoint,
                               Integer evidenceEndCodepoint, double confidence, String reviewStatus) {
    }

    /**
     * 一条章节内说话人归因片段。
     *
     * @param chapterIndex 章节序号
     * @param sequenceNumber 章节内稳定序号
     * @param textStartCodepoint 冻结正文中的起始 Unicode 码点偏移
     * @param textEndCodepoint 冻结正文中的结束 Unicode 码点偏移
     * @param speakerKind 说话人类别
     * @param speakerCanonicalName 角色稳定名称，可为空
     * @param deliveryTags 演绎标签
     * @param confidence 分析置信度
     * @param reviewStatus 审核状态
     * @param evidenceExcerpt 低置信度片段从冻结正文派生的受限审核摘录，可为空
     */
    public record SpeechSegment(int chapterIndex, int sequenceNumber, int textStartCodepoint,
                                int textEndCodepoint, String speakerKind, String speakerCanonicalName,
                                List<String> deliveryTags, double confidence, String reviewStatus,
                                String evidenceExcerpt) {
    }
}
