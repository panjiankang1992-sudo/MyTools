package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Executor 的 cgroup v2 任务级聚合资源限制配置。
 *
 * @param enabled 是否启用 cgroup v2
 * @param root 已委派给 Executor 用户的 cgroup 根目录
 * @param maximumMemoryBytes 整个任务进程树最大内存字节数
 * @param maximumProcesses 整个任务进程树最大进程数
 * @param maximumCpuPercent 整个任务进程树最大 CPU 百分比，100 表示一个逻辑 CPU
 */
@Validated
@ConfigurationProperties(prefix = "executor.cgroup-v2")
public record ExecutorCgroupProperties(
        boolean enabled,
        @NotNull Path root,
        @Min(0) long maximumMemoryBytes,
        @Min(0) int maximumProcesses,
        @Min(0) @Max(10000) int maximumCpuPercent
) {

    /**
     * 创建关闭状态的兼容配置。
     */
    public ExecutorCgroupProperties() {
        this(false, Path.of("/sys/fs/cgroup/mytools-executor"), 0, 0, 0);
    }
}
