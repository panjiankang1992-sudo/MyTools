package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Gateway 路由、认证和下游连接配置。
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(IdentityMode identityMode, boolean readerRouteEnabled,
                                Set<Long> readerTenantAllowlist,
                                String mytoolsUrl, String identityUrl, String readerUrl,
                                String internalToken, String identityToken, String readerToken,
                                int connectTimeoutMillis, int readTimeoutMillis) {

    /**
     * 判断 Reader 灰度路由是否允许指定主体。
     */
    public boolean readerTenantAllowed(long userId) {
        return readerRouteEnabled && readerTenantAllowlist != null && readerTenantAllowlist.contains(userId);
    }

    /**
     * 显式认证切换模式。
     */
    public enum IdentityMode {
        LEGACY,
        DUAL,
        IDENTITY
    }
}
