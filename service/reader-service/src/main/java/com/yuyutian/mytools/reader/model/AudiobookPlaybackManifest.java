package com.yuyutian.mytools.reader.model;

import java.util.List;
import java.util.UUID;

/**
 * 一个生成版本的可播放章节清单，不暴露受管存储地址。
 *
 * @param generationId 生成运行标识
 * @param generationVersion 生成版本号
 * @param status 生成运行状态
 * @param chapters 按章节序号排序的播放项
 */
public record AudiobookPlaybackManifest(UUID generationId, int generationVersion, String status,
                                        List<Chapter> chapters) {

    /**
     * 一个章节的播放可用性与稳定资产标识。
     *
     * @param index 章节序号
     * @param title 章节标题
     * @param availability 播放可用性
     * @param audioAssetId 已登记的音频资产标识
     * @param durationMs 音频时长
     */
    public record Chapter(int index, String title, String availability, UUID audioAssetId, Long durationMs) {
    }
}
