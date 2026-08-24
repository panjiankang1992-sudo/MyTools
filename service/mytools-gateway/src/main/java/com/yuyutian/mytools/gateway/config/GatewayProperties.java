package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Gateway 路由、认证和下游连接配置。
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(IdentityMode identityMode, boolean identityRouteEnabled,
                                boolean readerRouteEnabled,
                                Set<Long> readerTenantAllowlist,
                                boolean driveRouteEnabled, Set<Long> driveTenantAllowlist,
                                boolean downloadRouteEnabled, Set<Long> downloadTenantAllowlist,
                                String mytoolsUrl, String identityUrl, String readerUrl, String driveUrl,
                                String downloadUrl, String internalToken, String identityToken,
                                String readerToken, String driveToken, String downloadToken,
                                int connectTimeoutMillis, int readTimeoutMillis,
                                boolean mediaRouteEnabled, String mediaUrl, String mediaToken,
                                boolean messagingRouteEnabled, String messagingUrl, String messagingToken) {

    /**
     * 判断 Identity 登录入口与令牌校验模式是否形成可用组合。
     *
     * @return 是否允许签发新令牌
     */
    public boolean identityRouteUsable() {
        return identityRouteEnabled && identityMode != IdentityMode.LEGACY;
    }

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
     * 判断 Download 灰度路由是否允许指定主体。
     *
     * @param userId 用户标识
     * @return 是否允许
     */
    public boolean downloadTenantAllowed(long userId) {
        return downloadRouteEnabled && downloadTenantAllowlist != null
                && downloadTenantAllowlist.contains(userId);
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
