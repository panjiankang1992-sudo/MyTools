package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 一代有声书可审核的冻结音色计划。
 *
 * @param generationId 生成运行标识
 * @param qualityGateAttestationSha256 多角色音色启用时的质量门禁证明摘要；单旁白计划为空
 * @param bindings 旁白和角色音色绑定
 */
public record AudiobookVoicePlanView(UUID generationId, String qualityGateAttestationSha256,
                                     List<Binding> bindings) {

    /**
     * 创建不含多角色质量门禁证明的兼容单旁白视图。
     *
     * @param generationId 生成运行标识
     * @param bindings 旁白和角色音色绑定
     */
    public AudiobookVoicePlanView(UUID generationId, List<Binding> bindings) {
        this(generationId, null, bindings);
    }

    /**
     * 一条已冻结的音色绑定。
     *
     * @param roleKey 旁白或角色键
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
