package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 单独注册新闻配置，保持既有领域构造协议兼容。 */
@Configuration
@EnableConfigurationProperties(NewsGatewayProperties.class)
public class NewsGatewayConfiguration { }
