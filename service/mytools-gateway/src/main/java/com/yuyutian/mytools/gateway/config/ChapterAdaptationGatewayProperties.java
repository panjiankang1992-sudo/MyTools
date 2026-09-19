package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 改编读取和新建独立开关，未配置时均关闭，停止新建不影响已开放的历史和取消。 */
@ConfigurationProperties("gateway.chapter-adaptation")
public record ChapterAdaptationGatewayProperties(boolean readEnabled, boolean createEnabled) { }
