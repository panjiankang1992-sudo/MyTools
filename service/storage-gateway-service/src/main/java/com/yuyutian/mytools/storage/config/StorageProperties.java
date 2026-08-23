package com.yuyutian.mytools.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 存储网关安全配置。
 *
 * @param internalToken 内部接口令牌
 * @param defaultRootName 默认受管根名称
 * @param defaultRootPurpose 默认受管根用途
 * @param defaultRootPath 默认受管根路径
 * @param defaultRootNodeLabel 默认受管根节点亲和标签
 * @param defaultRootNodeValue 默认受管根节点亲和值
 * @param maximumUploadBytes 单次上传最大字节数
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String internalToken, String defaultRootName, String defaultRootPurpose,
                                Path defaultRootPath, String defaultRootNodeLabel, String defaultRootNodeValue,
                                long maximumUploadBytes) {
}
