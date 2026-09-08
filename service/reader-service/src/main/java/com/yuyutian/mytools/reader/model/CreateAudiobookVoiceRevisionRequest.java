package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 基于完成版本替换一条冻结音色并创建修订版的请求。
 *
 * @param idempotencyKey 调用方幂等键
 * @param roleKey 旁白或角色键
 * @param provider 已启用目录中的供应商标识
 * @param voiceType 已启用目录中的音色标识
 */
public record CreateAudiobookVoiceRevisionRequest(
        @NotBlank @Size(max = 255) String idempotencyKey,
        @NotBlank @Pattern(regexp = "NARRATOR|CHARACTER:.{1,256}") String roleKey,
        @NotBlank @Size(max = 64) String provider,
        @NotBlank @Size(max = 256) String voiceType) {
}
