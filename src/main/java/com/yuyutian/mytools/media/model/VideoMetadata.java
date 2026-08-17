package com.yuyutian.mytools.media.model;

/**
 * 视频探测得到的基础元数据。
 */
public record VideoMetadata(
        long durationMs,
        String format,
        String videoCodec,
        String audioCodec,
        int width,
        int height,
        double frameRate,
        long bitRate) {
}
