package com.yuyutian.mytools.cloudfile.model;

import java.time.Instant;

/**
 * 可交给系统播放器使用的短期远程媒体票据。
 *
 * @param ticket 随机票据
 * @param streamPath 播放端点相对路径
 * @param expiresAt 过期时间
 */
public record MediaPlaybackTicket(String ticket, String streamPath, Instant expiresAt) {
}
