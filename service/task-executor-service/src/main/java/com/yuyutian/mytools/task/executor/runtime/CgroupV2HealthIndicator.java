package com.yuyutian.mytools.task.executor.runtime;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * cgroup v2 任务级资源隔离健康检查。
 */
@Component("executorCgroupV2")
public class CgroupV2HealthIndicator implements HealthIndicator {

    private final CgroupV2Manager cgroupV2Manager;

    /**
     * 创建 cgroup v2 健康检查。
     *
     * @param cgroupV2Manager cgroup v2 管理器
     */
    public CgroupV2HealthIndicator(CgroupV2Manager cgroupV2Manager) {
        this.cgroupV2Manager = cgroupV2Manager;
    }

    /**
     * 返回 cgroup v2 委派可用性。
     *
     * @return 健康状态
     */
    @Override
    public Health health() {
        try {
            cgroupV2Manager.validate();
            return Health.up().build();
        } catch (IOException exception) {
            return Health.down().withDetail("error", "Executor cgroup v2 isolation is unavailable").build();
        }
    }
}
