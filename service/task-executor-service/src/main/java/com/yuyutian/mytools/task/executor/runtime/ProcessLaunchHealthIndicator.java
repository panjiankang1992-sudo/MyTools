package com.yuyutian.mytools.task.executor.runtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 验证当前 JVM 及脚本进程启动器能够真正启动子进程。 */
@Component("executorProcessLaunch")
public class ProcessLaunchHealthIndicator implements HealthIndicator, MeterBinder {

    private static final long PROBE_INTERVAL_NANOS = Duration.ofSeconds(5).toNanos();
    private final ScriptProcessRunner processRunner;
    private ProbeResult lastProbe;

    /**
     * 创建脚本进程启动健康检查。
     *
     * @param processRunner 脚本进程运行器
     */
    public ProcessLaunchHealthIndicator(ScriptProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /**
     * 返回实际子进程启动状态。
     *
     * @return 启动健康状态
     */
    @Override
    public Health health() {
        return canLaunch()
                ? Health.up().build()
                : Health.down().withDetail("error", "Executor cannot launch a script process").build();
    }

    /**
     * 暴露脚本启动检查结果。
     *
     * @param registry 指标注册表
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("task.executor.process.launch.ready", this, probe -> probe.canLaunch() ? 1 : 0)
                .description("Whether the executor can launch a script process")
                .register(registry);
    }

    synchronized boolean canLaunch() {
        long now = System.nanoTime();
        // 调度轮询和健康请求共用探测结果，避免每次轮询都创建子进程。
        if (lastProbe == null || now - lastProbe.checkedAtNanos() >= PROBE_INTERVAL_NANOS) {
            boolean launched = probe();
            // 调用者取消不能把共享的节点状态标记为故障。
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            lastProbe = new ProbeResult(launched, System.nanoTime());
        }
        return lastProbe.launched();
    }

    private boolean probe() {
        Process process = null;
        try {
            List<String> command = processRunner.preparedCommand(List.of("/usr/bin/true"));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            process = builder.start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record ProbeResult(boolean launched, long checkedAtNanos) {
    }
}
