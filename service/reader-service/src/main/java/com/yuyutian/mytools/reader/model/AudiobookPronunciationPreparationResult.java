package com.yuyutian.mytools.reader.model;

import java.util.UUID;

/**
 * 一次读音修订受影响章节计算的结果摘要。
 *
 * @param generationId 读音修订版本标识
 * @param affectedChapterCount 已被重新排队合成的章节数
 */
public record AudiobookPronunciationPreparationResult(UUID generationId, int affectedChapterCount) {
}
