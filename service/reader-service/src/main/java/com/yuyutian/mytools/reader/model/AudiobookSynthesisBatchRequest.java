package com.yuyutian.mytools.reader.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 执行器回写章节音频产物的受限批次。
 *
 * @param chapters 章节音频产物
 */
public record AudiobookSynthesisBatchRequest(
        @NotEmpty @Size(max = 50) List<@Valid Chapter> chapters) {

    /**
     * 一条已校验并发布的章节音频。
     *
     * @param index 章节序号
     * @param sourceContentSha256 对应正文快照摘要
     * @param assetId Asset Registry 已登记音频资产标识
     * @param storageUri 受管音频地址
     * @param contentSha256 音频摘要
     * @param format 音频格式
     * @param sizeBytes 音频字节数
     * @param durationMs 音频时长
     */
    public record Chapter(@PositiveOrZero int index,
                          @NotBlank @Size(min = 64, max = 64) String sourceContentSha256,
                          @NotNull UUID assetId,
                          @NotBlank @Size(max = 2048) String storageUri,
                          @NotBlank @Size(min = 64, max = 64) String contentSha256,
                          @NotBlank @Size(max = 16) String format,
                          @Positive long sizeBytes,
                          @Positive long durationMs) {
    }
}
