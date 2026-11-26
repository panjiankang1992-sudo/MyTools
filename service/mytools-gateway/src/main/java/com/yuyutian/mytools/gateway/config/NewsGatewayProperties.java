package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 新闻路由配置，默认关闭并使用独立内部令牌。 */
@ConfigurationProperties("gateway.news")
public record NewsGatewayProperties(@DefaultValue("false") boolean routeEnabled,
                                     @DefaultValue("http://127.0.0.1:23350") String url,
                                     @DefaultValue("") String token) { }
