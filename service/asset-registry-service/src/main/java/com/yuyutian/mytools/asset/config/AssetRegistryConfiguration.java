package com.yuyutian.mytools.asset.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 资产注册服务配置入口。
 */
@Configuration
@EnableConfigurationProperties(AssetRegistryProperties.class)
public class AssetRegistryConfiguration {
}
