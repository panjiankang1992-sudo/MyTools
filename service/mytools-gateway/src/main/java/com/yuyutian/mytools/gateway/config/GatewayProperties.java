package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 路由、认证和下游连接配置。
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(IdentityMode identityMode, boolean readerRouteEnabled,
                                String mytoolsUrl, String identityUrl, String readerUrl,
                                String internalToken, String identityToken, String readerToken,
                                int connectTimeoutMillis, int readTimeoutMillis) {

    /**
     * 显式认证切换模式。
     */
    public enum IdentityMode {
        LEGACY,
        DUAL,
        IDENTITY
    }
}
