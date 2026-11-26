package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** RSS 用户路由默认关闭，令牌仅由服务端配置。 */
@ConfigurationProperties("gateway.rss")
public record RssGatewayProperties(@DefaultValue("false") boolean routeEnabled,
                                    @DefaultValue("http://127.0.0.1:23360") String url,
                                    @DefaultValue("") String token) { }
