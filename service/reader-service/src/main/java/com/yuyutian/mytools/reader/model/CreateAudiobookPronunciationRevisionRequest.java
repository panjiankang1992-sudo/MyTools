package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 基于完成版本修订一个中文读音并创建不可变子版本的请求。
 *
 * @param idempotencyKey 调用方幂等键
 * @param term 需要精确匹配的中文词条
 * @param pinyin 带声调数字的拼音读法
 */
public record CreateAudiobookPronunciationRevisionRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Size(max = 256) @Pattern(regexp = "[\\p{IsHan}]{1,128}") String term,
        @NotBlank @Size(max = 512)
        @Pattern(regexp = "(?i)(?:[a-zvü]+[1-5])(?:\\s+[a-zvü]+[1-5]){0,127}") String pinyin) {
}
