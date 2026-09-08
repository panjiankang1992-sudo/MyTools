package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Executor 工作目录磁盘保护配置。
 *
 * @param minimumUsableBytes 允许领取任务的最小可用字节数
 * @param minimumUsablePercent 允许领取任务的最小可用空间百分比
 */
@Validated
@ConfigurationProperties(prefix = "executor.disk")
public record ExecutorDiskProperties(
        @Min(0) long minimumUsableBytes,
        @Min(0) @Max(99) int minimumUsablePercent
) {
}
