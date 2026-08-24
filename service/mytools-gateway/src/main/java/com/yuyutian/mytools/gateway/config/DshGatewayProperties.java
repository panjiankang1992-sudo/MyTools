package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DSH Gateway 路由配置。
 */
@ConfigurationProperties(prefix = "gateway.dsh")
public record DshGatewayProperties(boolean routeEnabled, String url, String token) {
}
