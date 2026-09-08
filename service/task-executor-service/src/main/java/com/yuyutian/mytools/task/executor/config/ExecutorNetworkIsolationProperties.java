package com.yuyutian.mytools.task.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Executor 脚本网络隔离配置。
 *
 * @param enabled 是否启用默认拒绝网络命名空间
 * @param bubblewrapPath bubblewrap 可执行文件
 * @param allowedDomains 按脚本包声明的目标域名；非空清单在域名网关落地前保持拒绝执行
 */
@ConfigurationProperties(prefix = "executor.network-isolation")
public record ExecutorNetworkIsolationProperties(
        boolean enabled,
        Path bubblewrapPath,
        Map<String, Set<String>> allowedDomains
) {

    /**
     * 创建关闭状态的兼容配置。
     */
    public ExecutorNetworkIsolationProperties() {
        this(false, Path.of("/usr/bin/bwrap"), Map.of());
    }
}
