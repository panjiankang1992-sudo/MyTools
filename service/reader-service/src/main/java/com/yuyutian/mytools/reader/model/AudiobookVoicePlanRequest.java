package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 执行器回写的冻结音色目录与全书角色音色绑定计划。
 *
 * @param voicePlanFingerprintSha256 音色计划规范化摘要
 * @param qualityGateAttestationSha256 多角色音色启用前的质量门禁证明摘要；单旁白计划为空
 * @param voices 本次任务可用的目录快照
 * @param bindings 旁白和角色音色绑定
 */
public record AudiobookVoicePlanRequest(
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String voicePlanFingerprintSha256,
        @Pattern(regexp = "[a-fA-F0-9]{64}") String qualityGateAttestationSha256,
        @NotNull @Size(min = 1, max = 500) List<@Valid Voice> voices,
        @NotNull @Size(min = 1, max = 5001) List<@Valid Binding> bindings) {

    /**
     * 创建不含多角色质量门禁证明的兼容单旁白计划。
     *
     * @param voicePlanFingerprintSha256 音色计划规范化摘要
     * @param voices 本次任务可用的目录快照
     * @param bindings 旁白和角色音色绑定
     */
    public AudiobookVoicePlanRequest(String voicePlanFingerprintSha256, List<Voice> voices,
                                     List<Binding> bindings) {
        this(voicePlanFingerprintSha256, null, voices, bindings);
    }

    /**
     * 一个可用音色的可审计元数据。
     *
     * @param provider 供应商标识
     * @param voiceType 供应商音色标识
     * @param language 语言
     * @param presentation 声音呈现类型
     * @param ageGroup 年龄段
     * @param styleTags 风格标签
     * @param narratorEligible 是否可作为旁白
     * @param catalogVersion 目录版本
     * @param ssmlSupported 所选非实时端点与该音色是否已验证支持受限读音 SSML
     * @param previewStorageUri 已审核样音的受管位置；未配置试听时为空
     * @param previewContentSha256 已审核样音摘要；未配置试听时为空
     * @param previewSizeBytes 已审核样音字节数；未配置试听时为空
     * @param previewFormat 已审核样音格式；未配置试听时为空
     * @param previewDurationMs 已审核样音时长；未配置试听时为空
     */
    public record Voice(@NotBlank @Size(max = 64) String provider,
                        @NotBlank @Size(max = 256) String voiceType,
                        @NotBlank @Size(max = 32) String language,
                        @NotBlank @Pattern(regexp = "FEMININE|MASCULINE|NON_BINARY|NEUTRAL|UNKNOWN") String presentation,
                        @NotBlank @Size(max = 32) String ageGroup,
                        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 64) String> styleTags,
                        boolean narratorEligible,
                        @NotBlank @Size(max = 64) String catalogVersion,
                        boolean ssmlSupported,
                        @Size(max = 2048) String previewStorageUri,
                        @Size(min = 64, max = 64) String previewContentSha256,
                        @jakarta.validation.constraints.Positive Long previewSizeBytes,
                        @Size(max = 16) String previewFormat,
                        @jakarta.validation.constraints.Positive Long previewDurationMs) {

        /**
         * 创建未配置样音的目录项，兼容已有执行器和调用方。
         *
         * @param provider 供应商标识
         * @param voiceType 音色标识
         * @param language 语言
         * @param presentation 声音呈现类型
         * @param ageGroup 年龄段
         * @param styleTags 风格标签
         * @param narratorEligible 是否可作为旁白
         * @param catalogVersion 目录版本
         */
        public Voice(String provider, String voiceType, String language, String presentation, String ageGroup,
                     List<String> styleTags, boolean narratorEligible, String catalogVersion) {
            this(provider, voiceType, language, presentation, ageGroup, styleTags, narratorEligible, catalogVersion,
                    false, null, null, null, null, null);
        }
    }

    /**
     * 一条已选择且被冻结的旁白或角色音色绑定。
     *
     * @param roleKey NARRATOR 或以 CHARACTER: 开头的角色键
     * @param characterCanonicalName 角色稳定名称，旁白时为空
     * @param provider 供应商标识
     * @param voiceType 供应商音色标识
     * @param matchScore 匹配分数
     * @param matchMethod 匹配方法版本
     * @param rationaleTags 可解释的规则标签
     * @param locked 是否冻结
     */
    public record Binding(@NotBlank @Pattern(regexp = "NARRATOR|CHARACTER:.{1,256}") String roleKey,
                          @Size(max = 256) String characterCanonicalName,
                          @NotBlank @Size(max = 64) String provider,
                          @NotBlank @Size(max = 256) String voiceType,
                          @DecimalMin("0.0000") @DecimalMax("1.0000") double matchScore,
                          @NotBlank @Size(max = 64) String matchMethod,
                          @NotNull @Size(max = 16) List<@NotBlank @Size(max = 64) String> rationaleTags,
                          boolean locked) {
    }
}
