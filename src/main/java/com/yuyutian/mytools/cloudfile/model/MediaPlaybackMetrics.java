package com.yuyutian.mytools.cloudfile.model;

/**
 * 播放票据的实时流量指标。
 *
 * @param transferredBytes 票据累计向客户端输出的字节数
 * @param activeStreams 当前活动流数量
 * @param lastTransferTime 最后一次输出数据的时间戳
 */
public record MediaPlaybackMetrics(long transferredBytes, int activeStreams, long lastTransferTime) {
}
