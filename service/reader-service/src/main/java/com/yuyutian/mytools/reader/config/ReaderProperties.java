package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阅读服务外部依赖配置。
 *
 * @param schedulerUrl 调度服务地址
 * @param internalToken Executor 调用内部接口的令牌
 * @param ebookStorageRoot 电子书受管存储根名称
 * @param runtimeBaseUrl 书源规则执行器地址
 * @param runtimeSecureKey 书源规则执行器密钥
 */
@ConfigurationProperties(prefix = "reader")
public record ReaderProperties(String schedulerUrl, String internalToken, String ebookStorageRoot,
                               String runtimeBaseUrl, String runtimeSecureKey) {
}
