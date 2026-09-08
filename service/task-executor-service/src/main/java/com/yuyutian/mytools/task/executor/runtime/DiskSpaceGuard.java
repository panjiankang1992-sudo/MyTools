package com.yuyutian.mytools.task.executor.runtime;

import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 监控 Executor 工作目录所在文件系统的可用空间。
 */
@Component("executorDiskSpace")
public class DiskSpaceGuard implements HealthIndicator {

    private final ExecutorDiskProperties properties;
    private final DiskUsageProbe probe;

    /**
     * 创建工作目录磁盘守卫。
     *
     * @param executorProperties Executor 配置
     * @param diskProperties 磁盘阈值配置
     */
    @Autowired
    public DiskSpaceGuard(ExecutorProperties executorProperties, ExecutorDiskProperties diskProperties) {
        this(diskProperties, () -> readUsage(executorProperties.workRoot()));
    }

    DiskSpaceGuard(ExecutorDiskProperties properties, DiskUsageProbe probe) {
        this.properties = properties;
        this.probe = probe;
    }

    /**
     * 判断当前空间是否允许领取新任务。
     *
     * @return 空间充足时返回 true
     */
    public boolean hasCapacity() {
        try {
            return evaluate(probe.read()).healthy();
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 返回工作目录磁盘健康状态。
     *
     * @return 磁盘健康状态
     */
    @Override
    public Health health() {
        try {
            DiskState state = evaluate(probe.read());
            Health.Builder builder = state.healthy() ? Health.up() : Health.down();
            return builder.withDetail("usableBytes", state.usage().usableBytes())
                    .withDetail("totalBytes", state.usage().totalBytes())
                    .withDetail("requiredUsableBytes", state.requiredUsableBytes())
                    .build();
        } catch (IOException exception) {
            return Health.down().withDetail("error", "Executor disk space is unreadable").build();
        }
    }

    private DiskState evaluate(DiskUsage usage) {
        if (usage.usableBytes() < 0 || usage.totalBytes() <= 0) {
            return new DiskState(false, usage, properties.minimumUsableBytes());
        }
        long percentageBytes = usage.totalBytes() / 100 * properties.minimumUsablePercent()
                + (usage.totalBytes() % 100 * properties.minimumUsablePercent() + 99) / 100;
        long required = Math.max(properties.minimumUsableBytes(), percentageBytes);
        return new DiskState(usage.usableBytes() >= required, usage, required);
    }

    private static DiskUsage readUsage(Path configuredRoot) throws IOException {
        Path path = configuredRoot.toAbsolutePath().normalize();
        // 部署初始化前目录可能尚不存在，沿父级查找其最终所在文件系统。
        while (path != null && !Files.exists(path)) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IOException("Executor work root has no existing ancestor");
        }
        FileStore store = Files.getFileStore(path);
        return new DiskUsage(store.getUsableSpace(), store.getTotalSpace());
    }

    @FunctionalInterface
    interface DiskUsageProbe {
        DiskUsage read() throws IOException;
    }

    record DiskUsage(long usableBytes, long totalBytes) {
    }

    private record DiskState(boolean healthy, DiskUsage usage, long requiredUsableBytes) {
    }
}
