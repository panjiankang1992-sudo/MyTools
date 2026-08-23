package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阅读服务外部依赖配置。
 *
 * @param schedulerUrl 调度服务地址
 */
@ConfigurationProperties(prefix = "reader")
public record ReaderProperties(String schedulerUrl) {
}
