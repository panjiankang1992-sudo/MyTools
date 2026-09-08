package com.yuyutian.mytools.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 有声书 App Gateway 契约。
 */
public final class AudiobookGatewayModels {

    private AudiobookGatewayModels() {
    }

    /**
     * App 创建有声书生成运行的请求，不允许提交所有者标识。
     *
     * @param ebookAssetId 已受管电子书资产标识
     * @param idempotencyKey 调用方幂等键
     * @param mode 生成范围
     * @param rightsConfirmed 是否确认拥有生成和保存音频的权利
     */
    public record CreateAudiobookGeneration(@NotNull UUID ebookAssetId,
                                            @NotBlank @Size(max = 255) String idempotencyKey,
                                            @NotBlank @Pattern(regexp = "FULL|INCREMENTAL|REPAIR") String mode,
                                            boolean rightsConfirmed) {
    }

    /**
     * App 发起的单音色修订请求，不允许提交所有者或任意目录元数据。
     *
     * @param idempotencyKey 调用方幂等键
     * @param roleKey 旁白或角色键
     * @param provider 已启用目录中的供应商标识
     * @param voiceType 已启用目录中的音色标识
     */
    public record CreateAudiobookVoiceRevision(@NotBlank @Size(max = 255) String idempotencyKey,
                                               @NotBlank @Pattern(regexp = "NARRATOR|CHARACTER:.{1,256}") String roleKey,
                                               @NotBlank @Size(max = 64) String provider,
                                               @NotBlank @Size(max = 256) String voiceType) {
    }

    /**
     * App 发起的低置信度说话人归因修订请求，不允许提交所有者、文本或任意角色定义。
     *
     * @param idempotencyKey 调用方幂等键
     * @param chapterIndex 冻结章节序号
     * @param sequenceNumber 章节内冻结片段序号
     * @param speakerKind 人工确认后的说话人类别
     * @param speakerCanonicalName 当前版本已有角色的稳定名称；非角色类别为空
     */
    public record CreateAudiobookSpeakerRevision(@NotBlank @Size(max = 255) String idempotencyKey,
                                                 @PositiveOrZero int chapterIndex,
                                                 @PositiveOrZero int sequenceNumber,
                                                 @NotBlank @Pattern(regexp = "CHARACTER|NARRATOR|UNKNOWN") String speakerKind,
                                                 @Size(max = 256) String speakerCanonicalName) {
    }

    /**
     * App 发起的中文读音修订请求。Gateway 只接收词条和读法，受限执行器在服务端冻结正文中计算命中章节。
     *
     * @param idempotencyKey 调用方幂等键
     * @param term 需精确匹配的中文词条
     * @param pinyin 以声调数字表示的拼音读法
     */
    public record CreateAudiobookPronunciationRevision(@NotBlank @Size(max = 255) String idempotencyKey,
                                                       @NotBlank @Size(max = 256)
                                                       @Pattern(regexp = "[\\p{IsHan}]{1,128}") String term,
                                                       @NotBlank @Size(max = 512)
                                                       @Pattern(regexp = "(?i)(?:[a-zvü]+[1-5])(?:\\s+[a-zvü]+[1-5]){0,127}")
                                                       String pinyin) {
    }

    /**
     * App 可见的有声书生成运行摘要。
     *
     * @param id 生成运行标识
     * @param status 运行状态
     * @param currentStage 当前阶段
     * @param mode 生成范围
     * @param generationVersion 生成版本
     * @param requestedChapterCount 请求章节数
     * @param completedChapterCount 已完成章节数
     * @param failedChapterCount 失败章节数
     * @param errorCode 稳定错误码
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record AudiobookGenerationView(UUID id, String status, String currentStage, String mode,
                                          int generationVersion, int requestedChapterCount,
                                          int completedChapterCount, int failedChapterCount, String errorCode,
                                          Instant createdAt, Instant updatedAt) {
    }

    /**
     * App 可查看的冻结读音词典；不包含正文、存储地址或供应商认证信息。
     *
     * @param generationId 有声书版本标识
     * @param fingerprintSha256 词典规范化摘要
     * @param entries 已冻结词条
     */
    public record AudiobookPronunciationDictionaryView(UUID generationId, String fingerprintSha256,
                                                       List<Entry> entries) {

        /**
         * 一条中文词条和它的拼音读法。
         *
         * @param term 中文词条
         * @param pinyin 带声调数字的拼音
         */
        public record Entry(String term, String pinyin) {
        }
    }

    /**
     * App 创建整书 ZIP 导出任务的请求。
     *
     * @param idempotencyKey 调用方幂等键
     * @param format 当前只支持 ZIP
     */
    public record CreateAudiobookExport(@NotBlank @Size(max = 255)
                                        @Pattern(regexp = "^[A-Za-z0-9._:-]{1,255}$") String idempotencyKey,
                                        @NotBlank @Pattern(regexp = "ZIP") String format) {
    }

    /**
     * App 可见的异步有声书导出任务摘要，不含存储路径和下载票据。
     *
     * @param id 导出标识
     * @param generationId 有声书版本标识
     * @param format 归档格式
     * @param status 导出状态
     * @param currentStage 当前阶段
     * @param chapterCount 归档章节数
     * @param sizeBytes 完成归档大小；未完成时为空
     * @param errorCode 稳定错误码；无错误时为空
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record AudiobookExportView(UUID id, UUID generationId, String format, String status, String currentStage,
                                      int chapterCount, Long sizeBytes, String errorCode, Instant createdAt,
                                      Instant updatedAt) {
    }

    /**
     * 一个有声书版本的章节播放清单，不携带存储位置或内部令牌。
     *
     * @param generationId 生成运行标识
     * @param generationVersion 生成版本
     * @param status 运行状态
     * @param chapters 章节清单
     */
    public record AudiobookPlaybackManifest(UUID generationId, int generationVersion, String status,
                                            List<Chapter> chapters) {

        /**
         * 一个章节的播放状态。
         *
         * @param index 章节序号
         * @param title 章节标题
         * @param availability 音频可用性
         * @param audioAssetId 稳定音频资产标识
         * @param durationMs 已校验音频时长
         */
        public record Chapter(int index, String title, String availability, UUID audioAssetId, Long durationMs) {
        }
    }

    /**
     * App 可审核的全书人物、关系与说话人归因结果。
     *
     * @param generationId 生成运行标识
     * @param analysisModelVersion 已执行的分析模型版本；历史记录使用兼容占位值
     * @param analysisRuleVersion 已执行的分析规则版本；历史记录使用兼容占位值
     * @param characters 角色档案
     * @param relationships 角色关系
     * @param speechSegments 说话人片段
     */
    public record AudiobookBookAnalysisView(UUID generationId, String analysisModelVersion, String analysisRuleVersion,
                                            List<Character> characters, List<Relationship> relationships,
                                            List<SpeechSegment> speechSegments) {

        /**
         * 创建兼容旧版本生成记录的分析视图。
         *
         * @param generationId 生成运行标识
         * @param characters 角色档案
         * @param relationships 角色关系
         * @param speechSegments 说话人片段
         */
        public AudiobookBookAnalysisView(UUID generationId, List<Character> characters,
                                         List<Relationship> relationships, List<SpeechSegment> speechSegments) {
            this(generationId, "UNKNOWN_LEGACY", "UNKNOWN_LEGACY", characters, relationships, speechSegments);
        }

        /**
         * 一个角色档案。
         *
         * @param canonicalName 稳定角色名称
         * @param displayName 展示名称
         * @param presentation 声音呈现类型
         * @param characterType 角色类型
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
         * 一个角色别名证据。
         *
         * @param alias 别名
         * @param aliasType 别名类型
         * @param evidenceChapterIndex 证据章节
         * @param evidenceStartCodepoint 证据起始码点
         * @param evidenceEndCodepoint 证据结束码点
         * @param confidence 分析置信度
         */
        public record Alias(String alias, String aliasType, Integer evidenceChapterIndex,
                            Integer evidenceStartCodepoint, Integer evidenceEndCodepoint, double confidence) {
        }

        /**
         * 一个有方向的角色关系。
         *
         * @param sourceCanonicalName 源角色名称
         * @param targetCanonicalName 目标角色名称
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
         * 一个章节内说话人归因片段。
         *
         * @param chapterIndex 章节序号
         * @param sequenceNumber 章节内序号
         * @param textStartCodepoint 起始码点偏移
         * @param textEndCodepoint 结束码点偏移
         * @param speakerKind 说话人类别
         * @param speakerCanonicalName 角色名称，可为空
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

    /**
     * App 可审核的冻结旁白与角色音色计划。
     *
     * @param generationId 生成运行标识
     * @param qualityGateAttestationSha256 多角色音色启用时的质量门禁证明摘要；单旁白计划为空
     * @param bindings 音色绑定
     */
    public record AudiobookVoicePlanView(UUID generationId, String qualityGateAttestationSha256,
                                         List<Binding> bindings) {

        /**
         * 创建不含多角色质量门禁证明的兼容单旁白视图。
         *
         * @param generationId 生成运行标识
         * @param bindings 音色绑定
         */
        public AudiobookVoicePlanView(UUID generationId, List<Binding> bindings) {
            this(generationId, null, bindings);
        }

        /**
         * 一条旁白或角色音色绑定。
         *
         * @param roleKey 角色键
         * @param characterCanonicalName 角色稳定名称，可为空
         * @param provider 供应商标识
         * @param voiceType 供应商音色标识
         * @param matchScore 匹配分数
         * @param matchMethod 匹配方法
         * @param rationaleTags 匹配理由标签
         * @param locked 是否冻结
         * @param reviewStatus 审核状态
         */
        public record Binding(String roleKey, String characterCanonicalName, String provider, String voiceType,
                              double matchScore, String matchMethod, List<String> rationaleTags, boolean locked,
                              String reviewStatus) {
        }
    }

    /**
     * App 审核页可读取的已启用音色目录，不携带供应商密钥。
     *
     * @param generationId 有声书版本标识
     * @param voices 可选择的音色目录项
     */
    public record AudiobookVoiceCatalogView(UUID generationId, List<Voice> voices) {

        /**
         * 一条受控音色目录项。
         *
         * @param provider 供应商标识
         * @param voiceType 音色标识
         * @param language 语言
         * @param presentation 声音呈现类型
         * @param ageGroup 年龄段
         * @param styleTags 风格标签
         * @param narratorEligible 是否可作为旁白
         * @param catalogVersion 目录版本
         * @param ssmlSupported 是否可用于受限读音词典
         * @param previewAvailable 是否存在可通过短期票据读取的已审核样音
         */
        public record Voice(String provider, String voiceType, String language, String presentation, String ageGroup,
                            List<String> styleTags, boolean narratorEligible, String catalogVersion,
                            boolean ssmlSupported,
                            boolean previewAvailable) {
        }
    }
}
