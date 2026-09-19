package com.yuyutian.mytools.task.executor.node;

import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerClient;
import com.yuyutian.mytools.task.executor.runtime.ExecutionReportJournal;
import com.yuyutian.mytools.task.executor.runtime.CgroupV2Manager;
import com.yuyutian.mytools.task.executor.runtime.NetworkIsolationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行节点注册和心跳代理。
 */
@Component
public class ExecutorNodeAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorNodeAgent.class);
    private final SchedulerClient schedulerNodeClient;
    private final ExecutionReportJournal reportJournal;
    private final CgroupV2Manager cgroupV2Manager;
    private final NetworkIsolationManager networkIsolationManager;
    private final UUID instanceId = UUID.randomUUID();
    private final AtomicReference<ExecutorNodeRegistration> registration = new AtomicReference<>();
    private final AtomicInteger runningTasks = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean diskDraining = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 创建执行节点代理。
     *
     * @param schedulerNodeClient 调度服务客户端
     */
    public ExecutorNodeAgent(SchedulerClient schedulerNodeClient) {
        this(schedulerNodeClient, null, null, null);
    }

    /**
     * 创建带启动恢复门禁的执行节点代理。
     *
     * @param schedulerNodeClient 调度服务客户端
     * @param reportJournal 执行结果持久日志
     */
    public ExecutorNodeAgent(SchedulerClient schedulerNodeClient, ExecutionReportJournal reportJournal) {
        this(schedulerNodeClient, reportJournal, null, null);
    }

    /**
     * 创建带启动恢复和资源隔离门禁的执行节点代理。
     *
     * @param schedulerNodeClient 调度服务客户端
     * @param reportJournal 执行结果持久日志
     * @param cgroupV2Manager cgroup v2 管理器
     */
    public ExecutorNodeAgent(SchedulerClient schedulerNodeClient, ExecutionReportJournal reportJournal,
                             CgroupV2Manager cgroupV2Manager) {
        this(schedulerNodeClient, reportJournal, cgroupV2Manager, null);
    }

    /**
     * 创建带完整启动恢复和隔离能力门禁的执行节点代理。
     *
     * @param schedulerNodeClient 调度服务客户端
     * @param reportJournal 执行结果持久日志
     * @param cgroupV2Manager cgroup v2 管理器
     * @param networkIsolationManager 网络隔离管理器
     */
    @Autowired
    public ExecutorNodeAgent(SchedulerClient schedulerNodeClient, ExecutionReportJournal reportJournal,
                             CgroupV2Manager cgroupV2Manager,
                             NetworkIsolationManager networkIsolationManager) {
        this.schedulerNodeClient = schedulerNodeClient;
        this.reportJournal = reportJournal;
        this.cgroupV2Manager = cgroupV2Manager;
        this.networkIsolationManager = networkIsolationManager;
    }

    /**
     * 注册节点或发送心跳。
     */
    @Scheduled(fixedDelayString = "${executor.heartbeat-seconds:10}000", initialDelay = 1000)
    public void maintainRegistration() {
        try {
            ExecutorNodeRegistration current = registration.get();
            if (current == null) {
                if (networkIsolationManager != null) {
                    // 启用后必须实际创建网络命名空间成功，不能只检查工具文件存在。
                    networkIsolationManager.validate();
                }
                if (cgroupV2Manager != null) {
                    // 配置启用但委派不可用时禁止注册，避免节点接单后全部执行失败。
                    cgroupV2Manager.validate();
                }
                if (reportJournal != null) {
                    // 旧进程可能在脚本运行中崩溃，先为无 Complete 的已领取 execution 合成失败终态。
                    reportJournal.recoverInterruptedExecutions();
                    // 节点对 Scheduler 可见前必须完成旧进程 WAL 回放，禁止新流量越过旧终态。
                    reportJournal.replayPending(schedulerNodeClient);
                    reportJournal.cleanupAcknowledgedWorkDirectories();
                }
                ExecutorNodeRegistration created = schedulerNodeClient.register(instanceId);
                registration.set(created);
                LOGGER.info("Executor node registered: nodeId={}, instanceId={}", created.id(), instanceId);
                return;
            }
            schedulerNodeClient.heartbeat(current.id(), instanceId, runningTasks.get());
        } catch (IOException exception) {
            LOGGER.warn("Executor node registration heartbeat failed: {}", exception.getMessage());
        }
    }

    /**
     * 返回本次启动实例标识。
     *
     * @return 启动实例标识
     */
    public UUID instanceId() {
        return instanceId;
    }

    /**
     * 返回已注册节点信息。
     *
     * @return 注册信息，尚未注册时为空
     */
    public ExecutorNodeRegistration registration() {
        return registration.get();
    }

    /**
     * 设置当前运行任务数。
     *
     * @param value 运行任务数
     */
    public void setRunningTasks(int value) {
        runningTasks.set(Math.max(value, 0));
    }

    /**
     * 因本地磁盘压力将节点切换为排空状态。
     */
    public void drainForDiskPressure() {
        ExecutorNodeRegistration current = registration.get();
        if (current == null || !diskDraining.compareAndSet(false, true)) {
            return;
        }
        try {
            // 状态写入必须携带本次启动实例，避免旧进程排空新进程复用的同名节点。
            schedulerNodeClient.updateNodeStatus(
                    current.id(), instanceId, "DRAINING", "EXECUTOR_DISK_PRESSURE");
        } catch (IOException exception) {
            diskDraining.set(false);
            LOGGER.warn("Failed to drain executor node after disk pressure: {}", exception.getMessage());
        }
    }
}
