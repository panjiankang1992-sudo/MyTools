package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Executor 子进程资源限制配置。
 *
 * @param maximumCpuSeconds 单个进程的最大 CPU 秒数，0 表示不限制
 * @param maximumVirtualMemoryBytes 单个进程的最大虚拟内存字节数，0 表示不限制
 * @param maximumFileBytes 单个文件的最大字节数，0 表示不限制
 */
@Validated
@ConfigurationProperties(prefix = "executor.resource-limits")
public record ExecutorResourceLimitProperties(
        @Min(0) long maximumCpuSeconds,
        @Min(0) long maximumVirtualMemoryBytes,
        @Min(0) long maximumFileBytes
) {

    /**
     * 判断是否启用了任一资源限制。
     *
     * @return 存在限制时返回 true
     */
    public boolean enabled() {
        return maximumCpuSeconds > 0 || maximumVirtualMemoryBytes > 0 || maximumFileBytes > 0;
    }
}
