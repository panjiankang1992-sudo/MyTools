package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 校验 Executor 进程未使用 root 身份运行。
 */
@Component("executorIdentity")
public class ExecutorIdentityGuard implements InitializingBean, HealthIndicator {

    private final ExecutorProperties properties;
    private final LongSupplier uidSupplier;
    private final Supplier<String> userSupplier;

    /**
     * 创建 Executor 运行身份守卫。
     *
     * @param properties Executor 配置
     */
    @Autowired
    public ExecutorIdentityGuard(ExecutorProperties properties) {
        this(properties, ExecutorIdentityGuard::currentUid, () -> System.getProperty("user.name", "unknown"));
    }

    ExecutorIdentityGuard(ExecutorProperties properties, LongSupplier uidSupplier, Supplier<String> userSupplier) {
        this.properties = properties;
        this.uidSupplier = uidSupplier;
        this.userSupplier = userSupplier;
    }

    /**
     * 在 Spring 上下文刷新及定时任务注册前校验运行身份。
     */
    @Override
    public void afterPropertiesSet() {
        verify();
    }

    /**
     * 返回运行身份安全状态。
     *
     * @return 身份健康状态
     */
    @Override
    public Health health() {
        long uid = uidSupplier.getAsLong();
        String user = userSupplier.get();
        if (isRoot(uid, user)) {
            return Health.down()
                    .withDetail("error", "Executor must not run as root")
                    .withDetail("enforced", properties.requireNonRoot())
                    .build();
        }
        return Health.up().withDetail("enforced", properties.requireNonRoot()).build();
    }

    void verify() {
        long uid = uidSupplier.getAsLong();
        String user = userSupplier.get();
        if (properties.requireNonRoot() && isRoot(uid, user)) {
            throw new IllegalStateException("Executor must not run as root");
        }
    }

    private static boolean isRoot(long uid, String user) {
        return uid == 0 || "root".equals(user);
    }

    private static long currentUid() {
        Path executable = Files.isExecutable(Path.of("/usr/bin/id"))
                ? Path.of("/usr/bin/id") : Path.of("/bin/id");
        if (!Files.isExecutable(executable)) {
            return -1;
        }
        try {
            Process process = new ProcessBuilder(executable.toString(), "-u").start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return -1;
            }
            return Long.parseLong(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException | InterruptedException | NumberFormatException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 无法读取 UID 时继续以 JVM 用户名作为兼容判断依据。
            return -1;
        }
    }
}
