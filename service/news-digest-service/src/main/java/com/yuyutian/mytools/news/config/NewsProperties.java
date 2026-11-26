package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.LocalTime;
import java.time.ZoneId;

/** 新闻服务配置，未设置令牌时接口保持关闭。 */
@ConfigurationProperties("news")
public record NewsProperties(String internalToken, String adminToken, boolean collectionEnabled,
                             ZoneId zone, LocalTime cutoffTime) {
    /** 验证时区与截止时刻，并禁止管理令牌和用户代理令牌混用。 */
    public NewsProperties {
        if (zone == null || cutoffTime == null) {
            // 缺失时间配置时启动失败，避免静默改变日报时间窗。
            throw new IllegalArgumentException("news.zone and news.cutoff-time are required");
        }
        if (adminToken != null && !adminToken.isBlank() && adminToken.equals(internalToken)) {
            // 来源管理不能由只负责用户代理的令牌完成。
            throw new IllegalArgumentException("news tokens must be distinct");
        }
    }
}
