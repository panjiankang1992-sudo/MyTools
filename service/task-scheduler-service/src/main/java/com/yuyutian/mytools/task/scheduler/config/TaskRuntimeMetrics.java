package com.yuyutian.mytools.task.scheduler.config;

import com.yuyutian.mytools.task.scheduler.service.TaskRuntimeMonitor;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调度队列与执行运行态 Micrometer 指标注册器。
 */
@Component
public class TaskRuntimeMetrics implements MeterBinder {

    private static final List<String> TERMINAL_STATUSES =
            List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");

    private final TaskRuntimeMonitor monitor;

    /**
     * 创建任务运行态指标注册器。
     *
     * @param monitor 任务运行态监测服务
     */
    public TaskRuntimeMetrics(TaskRuntimeMonitor monitor) {
        this.monitor = monitor;
    }

    /**
     * 注册队列、执行和失租指标。
     *
     * @param registry 指标注册表
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("task.queue.depth", monitor, TaskRuntimeMonitor::queueDepth)
                .description("Number of queued task instances")
                .register(registry);
        Gauge.builder("task.queue.wait.seconds", monitor, TaskRuntimeMonitor::oldestQueueWaitSeconds)
                .description("Age in seconds of the oldest queued task instance")
                .register(registry);
        Gauge.builder("task.execution.running", monitor, TaskRuntimeMonitor::runningExecutions)
                .description("Number of running executions according to the scheduler database")
                .register(registry);
        FunctionCounter.builder("task.lease.lost", monitor, TaskRuntimeMonitor::leaseLostExecutions)
                .description("Number of executions whose lease was lost")
                .register(registry);
        for (String status : TERMINAL_STATUSES) {
            // 固定终态标签集合，避免由请求内容产生无界指标基数。
            FunctionTimer.builder("task.execution", monitor,
                            value -> value.completedExecutionCount(status),
                            value -> value.completedExecutionSeconds(status), TimeUnit.SECONDS)
                    .tag("status", status)
                    .description("Completed task execution duration")
                    .register(registry);
        }
    }
}
