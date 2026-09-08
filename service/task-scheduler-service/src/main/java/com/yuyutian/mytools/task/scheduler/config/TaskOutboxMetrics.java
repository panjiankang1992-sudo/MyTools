package com.yuyutian.mytools.task.scheduler.config;

import com.yuyutian.mytools.task.scheduler.service.TaskOutboxMonitor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Outbox Micrometer 指标注册器。
 */
@Component
public class TaskOutboxMetrics implements MeterBinder {

    private final TaskOutboxMonitor monitor;

    /**
     * 创建 Outbox 指标注册器。
     *
     * @param monitor Outbox 监测服务
     */
    public TaskOutboxMetrics(TaskOutboxMonitor monitor) {
        this.monitor = monitor;
    }

    /**
     * 注册 Outbox 积压、最早年龄和死信指标。
     *
     * @param registry 指标注册表
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("task.outbox.backlog", monitor,
                        value -> value.snapshot(Instant.now()).backlog())
                .description("Number of task outbox events awaiting delivery")
                .register(registry);
        Gauge.builder("task.outbox.oldest.age.seconds", monitor,
                        value -> value.snapshot(Instant.now()).oldestAgeSeconds())
                .description("Age in seconds of the oldest unfinished task outbox event")
                .register(registry);
        Gauge.builder("task.outbox.dead", monitor,
                        value -> value.snapshot(Instant.now()).deadCount())
                .description("Number of dead task outbox events")
                .register(registry);
    }
}
