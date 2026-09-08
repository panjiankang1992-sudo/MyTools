package com.yuyutian.mytools.reader.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建有声书生成运行的请求。
 *
 * @param ownerId 所有者标识
 * @param ebookAssetId 已受管电子书资产标识
 * @param idempotencyKey 调用方幂等键
 * @param mode 生成范围
 * @param rightsConfirmed 是否已确认拥有生成和保存音频的权利
 */
public record CreateAudiobookGenerationRequest(@NotNull Long ownerId, @NotNull UUID ebookAssetId,
                                               @NotBlank @Size(max = 255) String idempotencyKey,
                                               @NotNull AudiobookGenerationMode mode,
                                               boolean rightsConfirmed) {
}
