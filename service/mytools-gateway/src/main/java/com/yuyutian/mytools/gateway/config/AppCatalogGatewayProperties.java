package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App Catalog Gateway 路由配置。
 */
@ConfigurationProperties(prefix = "gateway.app-catalog")
public record AppCatalogGatewayProperties(boolean routeEnabled, String url, String token) {
}
