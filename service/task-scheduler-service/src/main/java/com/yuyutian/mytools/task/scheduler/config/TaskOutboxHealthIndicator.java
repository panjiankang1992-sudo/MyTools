package com.yuyutian.mytools.task.scheduler.config;

import com.yuyutian.mytools.task.scheduler.service.TaskOutboxMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Outbox 积压与死信健康指示器。
 */
@Component("taskOutbox")
public class TaskOutboxHealthIndicator implements HealthIndicator {

    private final TaskOutboxMonitor monitor;
    private final TaskOutboxProperties properties;

    /**
     * 创建 Outbox 健康指示器。
     *
     * @param monitor Outbox 监测服务
     * @param properties Outbox 配置
     */
    public TaskOutboxHealthIndicator(TaskOutboxMonitor monitor, TaskOutboxProperties properties) {
        this.monitor = monitor;
        this.properties = properties;
    }

    /**
     * 根据死信与最早积压年龄评估健康状态。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        if (properties.webhookUrl() == null || properties.webhookUrl().isBlank()) {
            // 未启用 Webhook 时 Outbox 不承担投递职责，历史待投递事件不应阻断任务创建。
            return Health.up().withDetail("enabled", false).build();
        }
        TaskOutboxMonitor.OutboxSnapshot snapshot = monitor.snapshot(Instant.now());
        long threshold = properties.backlogAlertSeconds() > 0 ? properties.backlogAlertSeconds() : 300;
        Health.Builder builder = snapshot.deadCount() > 0
                || (snapshot.backlog() > 0 && snapshot.oldestAgeSeconds() >= threshold)
                ? Health.down() : Health.up();
        return builder.withDetail("backlog", snapshot.backlog())
                .withDetail("oldestAgeSeconds", snapshot.oldestAgeSeconds())
                .withDetail("deadCount", snapshot.deadCount())
                .withDetail("alertThresholdSeconds", threshold)
                .build();
    }
}
