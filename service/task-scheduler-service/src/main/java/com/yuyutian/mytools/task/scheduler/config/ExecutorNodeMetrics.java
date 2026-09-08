package com.yuyutian.mytools.task.scheduler.config;

import com.yuyutian.mytools.task.scheduler.service.ExecutorNodeMonitor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 执行节点可用性指标注册器。
 */
@Component
public class ExecutorNodeMetrics implements MeterBinder {

    private final ExecutorNodeMonitor monitor;

    /**
     * 创建执行节点可用性指标注册器。
     *
     * @param monitor 节点监测服务
     */
    public ExecutorNodeMetrics(ExecutorNodeMonitor monitor) {
        this.monitor = monitor;
    }

    /**
     * 注册节点状态、不可用集群和受阻任务指标。
     *
     * @param registry 指标注册表
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("task.executor.nodes.available", monitor,
                        value -> value.snapshot(Instant.now()).availableNodes())
                .description("Number of executor nodes eligible for new claims")
                .register(registry);
        Gauge.builder("task.executor.nodes.draining", monitor,
                        value -> value.snapshot(Instant.now()).drainingNodes())
                .description("Number of draining executor nodes")
                .register(registry);
        Gauge.builder("task.executor.nodes.offline", monitor,
                        value -> value.snapshot(Instant.now()).offlineNodes())
                .description("Number of offline executor nodes")
                .register(registry);
        Gauge.builder("task.executor.clusters.unavailable", monitor,
                        value -> value.snapshot(Instant.now()).unavailableClusters().size())
                .description("Number of enabled execution clusters without an available node")
                .register(registry);
        Gauge.builder("task.executor.tasks.blocked", monitor,
                        value -> value.snapshot(Instant.now()).blockedQueuedTasks())
                .description("Number of queued tasks blocked by unavailable executor clusters")
                .register(registry);
    }
}
