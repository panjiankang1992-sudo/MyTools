package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 自动音色匹配任务读取的已冻结人物档案和启用音色目录。
 *
 * @param generationId 生成运行标识
 * @param characters 已归并角色档案
 * @param voices 启用音色目录
 * @param lockedBindings 增量运行中必须保持的上一版本音色绑定
 */
public record AudiobookVoiceMatchInput(UUID generationId, List<Character> characters, List<Voice> voices,
                                       List<LockedBinding> lockedBindings) {

    /**
     * 创建不带增量锁定绑定的兼容音色匹配输入。
     *
     * @param generationId 生成运行标识
     * @param characters 已归并角色档案
     * @param voices 启用音色目录
     */
    public AudiobookVoiceMatchInput(UUID generationId, List<Character> characters, List<Voice> voices) {
        this(generationId, characters, voices, List.of());
    }

    /**
     * 可匹配的角色档案。
     *
     * @param canonicalName 稳定角色名称
     * @param presentation 声音呈现类型
     * @param characterType 角色类型
     * @param traits 人物特点标签
     * @param confidence 人物识别置信度
     */
    public record Character(String canonicalName, String presentation, String characterType, List<String> traits,
                            double confidence) {
    }

    /**
     * 一个启用的音色目录条目。
     *
     * @param provider 供应商标识
     * @param voiceType 供应商音色标识
     * @param language 语言
     * @param presentation 声音呈现类型
     * @param ageGroup 年龄段
     * @param styleTags 风格标签
     * @param narratorEligible 是否可作为旁白
     * @param catalogVersion 目录版本
     * @param ssmlSupported 非实时端点与此音色是否已验证支持受限读音 SSML
     */
    public record Voice(String provider, String voiceType, String language, String presentation, String ageGroup,
                        List<String> styleTags, boolean narratorEligible, String catalogVersion,
                        boolean ssmlSupported) {
    }

    /**
     * 一条来自可复用章节的冻结音色绑定。
     *
     * @param roleKey 旁白或角色键
     * @param characterCanonicalName 角色稳定名称，旁白时为空
     * @param provider 供应商标识
     * @param voiceType 音色标识
     * @param matchScore 原匹配分数
     * @param matchMethod 原匹配方法
     * @param rationaleTags 原匹配理由
     */
    public record LockedBinding(String roleKey, String characterCanonicalName, String provider, String voiceType,
                                double matchScore, String matchMethod, List<String> rationaleTags) {
    }
}
