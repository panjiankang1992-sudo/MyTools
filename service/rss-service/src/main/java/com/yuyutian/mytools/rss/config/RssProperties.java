package com.yuyutian.mytools.rss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 默认不轮询，内部令牌由部署环境注入。 */
@ConfigurationProperties("rss")
public record RssProperties(@DefaultValue("") String internalToken, @DefaultValue("false") boolean pollingEnabled) { }
