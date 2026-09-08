package com.yuyutian.mytools.task.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Executor 日志归档配置。
 */
@ConfigurationProperties(prefix = "executor.log-archive")
public record ExecutorLogArchiveProperties(boolean enabled, String storageGatewayUrl, String internalToken,
                                           String rootName, String relativePathPrefix) {

    /**
     * 校验启用时必需的归档配置。
     */
    public ExecutorLogArchiveProperties {
        if (enabled && (blank(storageGatewayUrl) || blank(internalToken) || blank(rootName)
                || blank(relativePathPrefix))) {
            throw new IllegalArgumentException("Executor log archive configuration is incomplete");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
