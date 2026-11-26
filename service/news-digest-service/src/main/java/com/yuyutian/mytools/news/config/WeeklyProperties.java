package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 手动任务独立开关，启用它不会启动自动日报或来源轮询。 */
@ConfigurationProperties("news.weekly")
public record WeeklyProperties(boolean enabled, String assetDirectory) { }
