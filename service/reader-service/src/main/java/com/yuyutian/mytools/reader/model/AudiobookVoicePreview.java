package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 已审核音色样音的内部受管定位信息。
 *
 * @param generationId 有声书生成运行标识
 * @param provider 音色供应商标识
 * @param voiceType 音色标识
 * @param storageUri 受管样音位置，仅允许内部服务使用
 * @param sizeBytes 样音字节数
 * @param format 样音格式
 */
public record AudiobookVoicePreview(UUID generationId, String provider, String voiceType,
                                    String storageUri, long sizeBytes, String format) {
}
