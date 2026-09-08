package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 执行器合成章节音频所需的受控输入。
 *
 * @param generationId 生成运行标识
 * @param narratorProvider 已冻结旁白供应商标识
 * @param narratorVoiceType 已冻结旁白音色标识
 * @param narratorSsmlSupported 已冻结旁白是否支持受限读音 SSML
 * @param pronunciations 已冻结、由服务端模板化渲染的读音词条
 * @param chapters 已冻结正文的章节清单
 */
public record AudiobookSynthesisInput(UUID generationId, String narratorProvider, String narratorVoiceType,
                                      boolean narratorSsmlSupported, List<Pronunciation> pronunciations,
                                      List<Chapter> chapters) {

    /**
     * 创建不带读音词典的兼容合成输入。
     *
     * @param generationId 生成运行标识
     * @param narratorProvider 已冻结旁白供应商标识
     * @param narratorVoiceType 已冻结旁白音色标识
     * @param chapters 已冻结正文的章节清单
     */
    public AudiobookSynthesisInput(UUID generationId, String narratorProvider, String narratorVoiceType,
                                   List<Chapter> chapters) {
        this(generationId, narratorProvider, narratorVoiceType, false, List.of(), chapters);
    }

    /**
     * 一条由审核词典提供的中文词条读法。
     *
     * @param term 精确匹配的中文词条
     * @param pinyin 带声调数字的拼音
     */
    public record Pronunciation(String term, String pinyin) {
    }

    /**
     * 一条可合成章节正文快照。
     *
     * @param index 章节序号
     * @param title 章节标题
     * @param contentSha256 已冻结正文摘要
     * @param textStorageUri 已冻结正文地址
     * @param textSizeBytes 已冻结正文字节数
     * @param segments 已归因说话人片段；空白区间使用旁白音色
     */
    public record Chapter(int index, String title, String contentSha256, String textStorageUri,
                          long textSizeBytes, List<Segment> segments) {
    }

    /**
     * 一条已冻结音色的章节文本片段。
     *
     * @param startCodepoint 冻结正文起始 Unicode 码点偏移
     * @param endCodepoint 冻结正文结束 Unicode 码点偏移
     * @param provider 本片段冻结供应商标识
     * @param voiceType 本片段供应商音色标识
     * @param ssmlSupported 本片段音色是否支持受限读音 SSML
     * @param speakerKind 说话人类别
     * @param confidence 说话人归因置信度
     */
    public record Segment(int startCodepoint, int endCodepoint, String provider, String voiceType,
                          boolean ssmlSupported, String speakerKind, double confidence) {
    }
}
