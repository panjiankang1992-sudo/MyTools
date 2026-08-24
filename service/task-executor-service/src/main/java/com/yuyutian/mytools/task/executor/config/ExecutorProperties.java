package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 任务执行节点配置。
 *
 * @param nodeName 节点名称
 * @param schedulerUrl 调度服务地址
 * @param workRoot 任务工作根目录
 * @param scriptRoot 脚本包根目录
 * @param pythonSdkRoot Python 任务 SDK 根目录
 * @param heartbeatSeconds 心跳间隔
 * @param pollSeconds 任务轮询间隔
 * @param leaseSeconds 执行租约秒数
 * @param maxConcurrentTasks 最大并发任务数
 * @param capabilities 节点能力
 * @param labels 节点标签
 * @param clusterNames 节点自动加入的集群名称
 * @param requirePackageIndex 是否强制校验不可变脚本发布索引
 * @param scriptEnvironments 按脚本包隔离的节点级环境变量
 */
@Validated
@ConfigurationProperties(prefix = "executor")
public record ExecutorProperties(
        @NotBlank String nodeName,
        @NotBlank String schedulerUrl,
        Path workRoot,
        Path scriptRoot,
        Path pythonSdkRoot,
        @Min(1) long heartbeatSeconds,
        @Min(1) long pollSeconds,
        @Min(10) int leaseSeconds,
        @Min(1) int maxConcurrentTasks,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        Set<String> clusterNames,
        boolean requirePackageIndex,
        Map<String, Map<String, String>> scriptEnvironments
) {
}
