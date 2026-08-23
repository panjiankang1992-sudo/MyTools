package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 任务执行节点配置。
 *
 * @param nodeName 节点名称
 * @param schedulerUrl 调度服务地址
 * @param workRoot 任务工作根目录
 * @param heartbeatSeconds 心跳间隔
 */
@Validated
@ConfigurationProperties(prefix = "executor")
public record ExecutorProperties(
        @NotBlank String nodeName,
        @NotBlank String schedulerUrl,
        Path workRoot,
        @Min(1) long heartbeatSeconds
) {
}
