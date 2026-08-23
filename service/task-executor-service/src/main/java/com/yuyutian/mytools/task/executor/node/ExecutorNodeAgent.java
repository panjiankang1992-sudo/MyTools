package com.yuyutian.mytools.task.executor.node;

import com.yuyutian.mytools.task.executor.client.ExecutorNodeRegistration;
import com.yuyutian.mytools.task.executor.client.SchedulerNodeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final SchedulerNodeClient schedulerNodeClient;
    private final UUID instanceId = UUID.randomUUID();
    private final AtomicReference<ExecutorNodeRegistration> registration = new AtomicReference<>();
    private final AtomicInteger runningTasks = new AtomicInteger();

    /**
     * 创建执行节点代理。
     *
     * @param schedulerNodeClient 调度服务客户端
     */
    public ExecutorNodeAgent(SchedulerNodeClient schedulerNodeClient) {
        this.schedulerNodeClient = schedulerNodeClient;
    }

    /**
     * 注册节点或发送心跳。
     */
    @Scheduled(fixedDelayString = "${executor.heartbeat-seconds:10}000", initialDelay = 1000)
    public void maintainRegistration() {
        try {
            ExecutorNodeRegistration current = registration.get();
            if (current == null) {
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
}
