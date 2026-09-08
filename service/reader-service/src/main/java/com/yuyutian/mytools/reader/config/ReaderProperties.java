package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 阅读服务外部依赖配置。
 *
 * @param schedulerUrl 调度服务地址
 * @param internalToken Executor 调用内部接口的令牌
 * @param ebookStorageRoot 电子书受管存储根名称
 * @param runtimeBaseUrl 书源规则执行器地址
 * @param runtimeSecureKey 书源规则执行器密钥
 * @param storageGatewayUrl 受管存储网关地址
 * @param storageInternalToken 受管存储内部访问令牌
 * @param audiobookMaximumCharactersPerGeneration 单次有声书生成允许冻结的最大 Unicode 码点数
 * @param audiobookDailyCharactersPerOwner 单个所有者每日允许提交合成的最大 Unicode 码点数
 * @param audiobookGenerationEnabled 是否接受新的有声书生成、修订和恢复任务
 * @param audiobookAllowedOwnerIds 允许提交新有声书工作的所有者白名单，空集合表示不限制
 */
@ConfigurationProperties(prefix = "reader")
public record ReaderProperties(String schedulerUrl, String internalToken, String ebookStorageRoot,
                               String runtimeBaseUrl, String runtimeSecureKey, String storageGatewayUrl,
                               String storageInternalToken, long audiobookMaximumCharactersPerGeneration,
                               long audiobookDailyCharactersPerOwner, boolean audiobookGenerationEnabled,
                               Set<Long> audiobookAllowedOwnerIds) {
}
