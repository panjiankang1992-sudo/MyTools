package com.yuyutian.mytools.task.executor.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 任务执行节点配置。
 *
 * @param nodeName 节点名称
 * @param schedulerUrl 调度服务地址
 * @param internalToken 调度服务内部鉴权令牌
 * @param workRoot 任务工作根目录
 * @param scriptRoot 脚本包根目录
 * @param pythonSdkRoot Python 任务 SDK 根目录
 * @param pythonExecutable Python 任务解释器
 * @param heartbeatSeconds 心跳间隔
 * @param pollSeconds 任务轮询间隔
 * @param leaseSeconds 执行租约秒数
 * @param leaseSafetySeconds 心跳失联安全窗口秒数
 * @param maxConcurrentTasks 最大并发任务数
 * @param successfulWorkRetentionSeconds 成功任务工作目录保留秒数
 * @param capabilities 节点能力
 * @param labels 节点标签
 * @param clusterNames 节点自动加入的集群名称
 * @param requirePackageIndex 是否强制校验不可变脚本发布索引
 * @param requireNonRoot 是否禁止以 root 身份启动
 * @param scriptEnvironments 按脚本包隔离的节点级环境变量
 */
@Validated
@ConfigurationProperties(prefix = "executor")
public record ExecutorProperties(
        @NotBlank String nodeName,
        @NotBlank String schedulerUrl,
        String internalToken,
        Path workRoot,
        Path scriptRoot,
        Path pythonSdkRoot,
        Path pythonExecutable,
        @Min(1) long heartbeatSeconds,
        @Min(1) long pollSeconds,
        @Min(10) int leaseSeconds,
        @Min(2) long leaseSafetySeconds,
        @Min(1) int maxConcurrentTasks,
        @Min(0) long successfulWorkRetentionSeconds,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        Set<String> clusterNames,
        boolean requirePackageIndex,
        boolean requireNonRoot,
        Map<String, Map<String, String>> scriptEnvironments
) {
    /**
     * 校验租约相关时间约束。
     */
    @ConstructorBinding
    public ExecutorProperties {
        if (heartbeatSeconds >= leaseSafetySeconds || leaseSafetySeconds >= leaseSeconds) {
            throw new IllegalArgumentException("Executor lease timing must satisfy heartbeat < safety < lease");
        }
    }

    /**
     * 创建兼容旧调用方的节点配置。
     */
    public ExecutorProperties(String nodeName, String schedulerUrl, Path workRoot, Path scriptRoot,
                              Path pythonSdkRoot, Path pythonExecutable, long heartbeatSeconds, long pollSeconds,
                              int leaseSeconds, int maxConcurrentTasks, Map<String, Object> capabilities,
                              Map<String, Object> labels, Set<String> clusterNames, boolean requirePackageIndex,
                              Map<String, Map<String, String>> scriptEnvironments) {
        this(nodeName, schedulerUrl, "", workRoot, scriptRoot, pythonSdkRoot, pythonExecutable, heartbeatSeconds,
                pollSeconds, leaseSeconds, Math.max(heartbeatSeconds + 1, leaseSeconds / 2L),
                maxConcurrentTasks, 0, capabilities, labels, clusterNames, requirePackageIndex, false,
                scriptEnvironments);
    }

    /**
     * 创建兼容带内部令牌旧调用方的节点配置。
     */
    public ExecutorProperties(String nodeName, String schedulerUrl, String internalToken, Path workRoot,
                              Path scriptRoot, Path pythonSdkRoot, Path pythonExecutable, long heartbeatSeconds,
                              long pollSeconds, int leaseSeconds, long leaseSafetySeconds, int maxConcurrentTasks,
                              Map<String, Object> capabilities, Map<String, Object> labels, Set<String> clusterNames,
                              boolean requirePackageIndex,
                              Map<String, Map<String, String>> scriptEnvironments) {
        this(nodeName, schedulerUrl, internalToken, workRoot, scriptRoot, pythonSdkRoot, pythonExecutable,
                heartbeatSeconds, pollSeconds, leaseSeconds, leaseSafetySeconds, maxConcurrentTasks, 0,
                capabilities, labels, clusterNames, requirePackageIndex, false, scriptEnvironments);
    }

    /**
     * 创建兼容带工作目录保留期的旧调用方节点配置。
     */
    public ExecutorProperties(String nodeName, String schedulerUrl, String internalToken, Path workRoot,
                              Path scriptRoot, Path pythonSdkRoot, Path pythonExecutable, long heartbeatSeconds,
                              long pollSeconds, int leaseSeconds, long leaseSafetySeconds, int maxConcurrentTasks,
                              long successfulWorkRetentionSeconds, Map<String, Object> capabilities,
                              Map<String, Object> labels, Set<String> clusterNames, boolean requirePackageIndex,
                              Map<String, Map<String, String>> scriptEnvironments) {
        this(nodeName, schedulerUrl, internalToken, workRoot, scriptRoot, pythonSdkRoot, pythonExecutable,
                heartbeatSeconds, pollSeconds, leaseSeconds, leaseSafetySeconds, maxConcurrentTasks,
                successfulWorkRetentionSeconds, capabilities, labels, clusterNames, requirePackageIndex, false,
                scriptEnvironments);
    }
}
