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
 * @param pollSeconds 兼容的任务轮询秒数，零表示未配置
 * @param pollMilliseconds 任务轮询毫秒数，零表示未配置
 * @param leaseSeconds 执行租约秒数
 * @param leaseSafetySeconds 心跳失联安全窗口秒数
 * @param maxConcurrentTasks 最大并发任务数
 * @param reservedChildTaskSlots 子任务保留槽位数，零表示自动计算
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
        @Min(0) long pollSeconds,
        @Min(0) long pollMilliseconds,
        @Min(10) int leaseSeconds,
        @Min(2) long leaseSafetySeconds,
        @Min(1) int maxConcurrentTasks,
        @Min(0) int reservedChildTaskSlots,
        @Min(0) long successfulWorkRetentionSeconds,
        Map<String, Object> capabilities,
        Map<String, Object> labels,
        Set<String> clusterNames,
        boolean requirePackageIndex,
        boolean requireNonRoot,
        Map<String, Map<String, String>> scriptEnvironments
) {
    private static final int PUBLISHED_CHILD_CHAIN_DEPTH = 3;

    /**
     * 校验租约相关时间约束。
     */
    @ConstructorBinding
    public ExecutorProperties {
        if (heartbeatSeconds >= leaseSafetySeconds || leaseSafetySeconds >= leaseSeconds) {
            throw new IllegalArgumentException("Executor lease timing must satisfy heartbeat < safety < lease");
        }
        if (reservedChildTaskSlots >= maxConcurrentTasks && reservedChildTaskSlots != 0) {
            throw new IllegalArgumentException("Reserved child task slots must be lower than maximum concurrency");
        }
        int minimumChildSlots = Math.min(Math.max(0, maxConcurrentTasks - 1), PUBLISHED_CHILD_CHAIN_DEPTH);
        if (reservedChildTaskSlots != 0 && reservedChildTaskSlots < minimumChildSlots) {
            throw new IllegalArgumentException("Reserved child task slots cannot cover published task chains");
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
                pollSeconds, 0, leaseSeconds, Math.max(heartbeatSeconds + 1, leaseSeconds / 2L),
                maxConcurrentTasks, 0, 0, capabilities, labels, clusterNames, requirePackageIndex, false,
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
                heartbeatSeconds, pollSeconds, 0, leaseSeconds, leaseSafetySeconds, maxConcurrentTasks, 0,
                0, capabilities, labels, clusterNames, requirePackageIndex, false, scriptEnvironments);
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
                heartbeatSeconds, pollSeconds, 0, leaseSeconds, leaseSafetySeconds, maxConcurrentTasks,
                0, successfulWorkRetentionSeconds, capabilities, labels, clusterNames, requirePackageIndex, false,
                scriptEnvironments);
    }

    /**
     * 创建兼容尚未提供毫秒轮询配置的完整节点配置。
     */
    public ExecutorProperties(String nodeName, String schedulerUrl, String internalToken, Path workRoot,
                              Path scriptRoot, Path pythonSdkRoot, Path pythonExecutable, long heartbeatSeconds,
                              long pollSeconds, int leaseSeconds, long leaseSafetySeconds, int maxConcurrentTasks,
                              long successfulWorkRetentionSeconds, Map<String, Object> capabilities,
                              Map<String, Object> labels, Set<String> clusterNames, boolean requirePackageIndex,
                              boolean requireNonRoot, Map<String, Map<String, String>> scriptEnvironments) {
        this(nodeName, schedulerUrl, internalToken, workRoot, scriptRoot, pythonSdkRoot, pythonExecutable,
                heartbeatSeconds, pollSeconds, 0, leaseSeconds, leaseSafetySeconds, maxConcurrentTasks,
                0, successfulWorkRetentionSeconds, capabilities, labels, clusterNames, requirePackageIndex,
                requireNonRoot, scriptEnvironments);
    }

    /**
     * 返回实际任务轮询间隔，新毫秒配置优先，其次兼容旧秒配置，均未配置时为二百五十毫秒。
     *
     * @return 实际轮询毫秒数
     */
    public long effectivePollMilliseconds() {
        if (pollMilliseconds > 0) {
            return pollMilliseconds;
        }
        if (pollSeconds > 0) {
            return Math.multiplyExact(pollSeconds, 1_000L);
        }
        return 250L;
    }

    /**
     * 返回子任务保留槽位数，默认使用总并发的一半且至少保留一个槽位。
     *
     * @return 子任务保留槽位数
     */
    public int effectiveReservedChildTaskSlots() {
        if (maxConcurrentTasks < 2) {
            return 0;
        }
        if (reservedChildTaskSlots > 0) {
            return reservedChildTaskSlots;
        }
        int minimumChildSlots = Math.min(maxConcurrentTasks - 1, PUBLISHED_CHILD_CHAIN_DEPTH);
        return Math.max(minimumChildSlots, maxConcurrentTasks / 2);
    }
}
