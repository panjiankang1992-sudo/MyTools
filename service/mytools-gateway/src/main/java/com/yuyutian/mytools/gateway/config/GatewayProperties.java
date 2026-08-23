package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Gateway 路由、认证和下游连接配置。
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(IdentityMode identityMode, boolean readerRouteEnabled,
                                Set<Long> readerTenantAllowlist,
                                boolean driveRouteEnabled, Set<Long> driveTenantAllowlist,
                                String mytoolsUrl, String identityUrl, String readerUrl, String driveUrl,
                                String internalToken, String identityToken, String readerToken, String driveToken,
                                int connectTimeoutMillis, int readTimeoutMillis) {

    /**
     * 判断 Reader 灰度路由是否允许指定主体。
     */
    public boolean readerTenantAllowed(long userId) {
        return readerRouteEnabled && readerTenantAllowlist != null && readerTenantAllowlist.contains(userId);
    }

    /**
     * 判断 Drive 灰度路由是否允许指定主体。
     *
     * @param userId 用户标识
     * @return 是否允许
     */
    public boolean driveTenantAllowed(long userId) {
        return driveRouteEnabled && driveTenantAllowlist != null && driveTenantAllowlist.contains(userId);
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
