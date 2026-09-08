package com.yuyutian.mytools.task.scheduler.config;

import com.yuyutian.mytools.task.scheduler.service.ExecutorNodeMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 执行节点与集群可用性健康指示器。
 */
@Component("executorNodes")
public class ExecutorNodeHealthIndicator implements HealthIndicator {

    private final ExecutorNodeMonitor monitor;
    private final NodeHealthProperties properties;

    /**
     * 创建执行节点健康指示器。
     *
     * @param monitor 节点监测服务
     * @param properties 节点健康配置
     */
    public ExecutorNodeHealthIndicator(ExecutorNodeMonitor monitor, NodeHealthProperties properties) {
        this.monitor = monitor;
        this.properties = properties;
    }

    /**
     * 按当前时间评估节点健康状态。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        return health(Instant.now());
    }

    /**
     * 按指定时间评估节点健康状态。
     *
     * @param now 当前时间
     * @return 健康状态
     */
    public Health health(Instant now) {
        ExecutorNodeMonitor.NodeAvailabilitySnapshot snapshot = monitor.snapshot(now);
        long threshold = properties.noAvailableAlertSeconds();
        boolean unavailable = snapshot.blockedQueuedTasks() > 0
                && snapshot.oldestBlockedAgeSeconds() >= threshold;
        Health.Builder builder = unavailable ? Health.down() : Health.up();
        return builder.withDetail("availableNodes", snapshot.availableNodes())
                .withDetail("drainingNodes", snapshot.drainingNodes())
                .withDetail("offlineNodes", snapshot.offlineNodes())
                .withDetail("unavailableClusters", snapshot.unavailableClusters())
                .withDetail("blockedQueuedTasks", snapshot.blockedQueuedTasks())
                .withDetail("oldestBlockedAgeSeconds", snapshot.oldestBlockedAgeSeconds())
                .withDetail("alertThresholdSeconds", threshold)
                .build();
    }
}
