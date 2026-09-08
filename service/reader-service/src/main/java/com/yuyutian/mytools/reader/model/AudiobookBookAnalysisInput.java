package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 全书人物与说话人分析任务所需的冻结章节输入。
 *
 * @param generationId 生成运行标识
 * @param chapters 本次需要模型分析的已冻结正文章节清单
 * @param inheritedAnalysis 可复用章节继承的受控全书分析事实
 */
public record AudiobookBookAnalysisInput(UUID generationId, List<Chapter> chapters,
                                         InheritedAnalysis inheritedAnalysis) {

    /**
     * 创建不含可复用事实的兼容分析输入。
     *
     * @param generationId 生成运行标识
     * @param chapters 已冻结正文的章节清单
     */
    public AudiobookBookAnalysisInput(UUID generationId, List<Chapter> chapters) {
        this(generationId, chapters, InheritedAnalysis.empty());
    }

    /**
     * 上一完成版本中可安全继承的分析事实。
     *
     * @param characters 继承的人物档案
     * @param relationships 继承的人物关系
     * @param speechSegments 继承且已重映射章节序号的说话人片段
     */
    public record InheritedAnalysis(List<Character> characters, List<Relationship> relationships,
                                    List<SpeechSegment> speechSegments) {

        /**
         * 返回没有可继承事实的稳定空值。
         *
         * @return 空继承分析结果
         */
        public static InheritedAnalysis empty() {
            return new InheritedAnalysis(List.of(), List.of(), List.of());
        }
    }

    /**
     * 一条已验证、可供本次全书归并的继承人物档案。
     *
     * @param canonicalName 稳定人物名称
     * @param displayName 展示名称
     * @param presentation 声音呈现类型
     * @param characterType 人物类型
     * @param traits 人物特点
     * @param firstChapterIndex 首次出现章节
     * @param occurrenceCount 出现次数估计
     * @param confidence 分析置信度
     * @param aliases 带证据的别名
     */
    public record Character(String canonicalName, String displayName, String presentation, String characterType,
                            List<String> traits, int firstChapterIndex, int occurrenceCount, double confidence,
                            List<Alias> aliases) {
    }

    /**
     * 一条可继承的人物别名证据。
     *
     * @param alias 别名
     * @param aliasType 别名类型
     * @param evidenceChapterIndex 证据章节
     * @param evidenceStartCodepoint 证据起始码点
     * @param evidenceEndCodepoint 证据结束码点
     * @param confidence 分析置信度
     */
    public record Alias(String alias, String aliasType, int evidenceChapterIndex, int evidenceStartCodepoint,
                        int evidenceEndCodepoint, double confidence) {
    }

    /**
     * 一条可继承的人物关系证据。
     *
     * @param sourceCanonicalName 源人物稳定名称
     * @param targetCanonicalName 目标人物稳定名称
     * @param relationshipType 关系类型
     * @param direction 关系方向
     * @param evidenceChapterIndex 证据章节
     * @param evidenceStartCodepoint 证据起始码点
     * @param evidenceEndCodepoint 证据结束码点
     * @param confidence 分析置信度
     */
    public record Relationship(String sourceCanonicalName, String targetCanonicalName, String relationshipType,
                               String direction, int evidenceChapterIndex, int evidenceStartCodepoint,
                               int evidenceEndCodepoint, double confidence) {
    }

    /**
     * 一条可继承、章节序号已映射到当前版本的说话人片段。
     *
     * @param chapterIndex 当前版本章节序号
     * @param sequenceNumber 章节内片段序号
     * @param textStartCodepoint 起始码点
     * @param textEndCodepoint 结束码点
     * @param speakerKind 说话人类别
     * @param speakerCanonicalName 人物稳定名称，可为空
     * @param deliveryTags 演绎标签
     * @param confidence 分析置信度
     * @param evidenceExcerpt 受限低置信度摘录，可为空
     */
    public record SpeechSegment(int chapterIndex, int sequenceNumber, int textStartCodepoint,
                                int textEndCodepoint, String speakerKind, String speakerCanonicalName,
                                List<String> deliveryTags, double confidence, String evidenceExcerpt) {
    }

    /**
     * 一条可分析的章节正文快照。
     *
     * @param index 章节序号
     * @param title 章节标题
     * @param contentSha256 已冻结正文摘要
     * @param textStorageUri 已冻结正文地址
     * @param textSizeBytes 已冻结正文字节数
     */
    public record Chapter(int index, String title, String contentSha256, String textStorageUri,
                          long textSizeBytes) {
    }
}
