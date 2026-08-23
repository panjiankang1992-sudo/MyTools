package com.yuyutian.mytools.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资产注册服务配置。
 */
@ConfigurationProperties(prefix = "asset-registry")
public record AssetRegistryProperties(String internalToken) {
}
