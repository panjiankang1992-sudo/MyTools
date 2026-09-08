package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 基于完成版本修订一条低置信度说话人归因并创建不可变版本的请求。
 *
 * @param idempotencyKey 调用方幂等键
 * @param chapterIndex 冻结章节序号
 * @param sequenceNumber 章节内冻结片段序号
 * @param speakerKind 人工确认后的说话人类别
 * @param speakerCanonicalName 角色稳定名称；仅角色类别允许提供
 */
public record CreateAudiobookSpeakerRevisionRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @PositiveOrZero int chapterIndex,
        @PositiveOrZero int sequenceNumber,
        @NotBlank @Pattern(regexp = "CHARACTER|NARRATOR|UNKNOWN") String speakerKind,
        @Size(max = 256) String speakerCanonicalName) {
}
