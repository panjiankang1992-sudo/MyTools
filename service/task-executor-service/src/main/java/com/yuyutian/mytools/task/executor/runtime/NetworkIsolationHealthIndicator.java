package com.yuyutian.mytools.task.executor.runtime;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 脚本默认拒绝网络隔离健康检查。
 */
@Component("executorNetworkIsolation")
public class NetworkIsolationHealthIndicator implements HealthIndicator {

    private final NetworkIsolationManager networkIsolationManager;

    /**
     * 创建网络隔离健康检查。
     *
     * @param networkIsolationManager 网络隔离管理器
     */
    public NetworkIsolationHealthIndicator(NetworkIsolationManager networkIsolationManager) {
        this.networkIsolationManager = networkIsolationManager;
    }

    /**
     * 返回网络命名空间实际可用性。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        try {
            networkIsolationManager.validate();
            return Health.up().build();
        } catch (IOException exception) {
            return Health.down().withDetail("error", "Executor network isolation is unavailable").build();
        }
    }
}
