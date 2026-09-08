package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 一代有声书审核时可选择的已启用音色目录。
 *
 * @param generationId 有声书版本标识
 * @param voices 已启用音色列表
 */
public record AudiobookVoiceCatalogView(UUID generationId, List<Voice> voices) {

    /**
     * 一个可人工选择的受控音色目录项。
     *
     * @param provider 供应商标识
     * @param voiceType 音色标识
     * @param language 语言
     * @param presentation 声音呈现类型
     * @param ageGroup 年龄段
     * @param styleTags 风格标签
     * @param narratorEligible 是否允许作为旁白
     * @param catalogVersion 目录版本
     * @param ssmlSupported 是否可用于读音词典修订
     * @param previewAvailable 是否存在可通过短期票据读取的已审核样音
     */
    public record Voice(String provider, String voiceType, String language, String presentation, String ageGroup,
                        List<String> styleTags, boolean narratorEligible, String catalogVersion,
                        boolean ssmlSupported,
                        boolean previewAvailable) {
    }
}
