package com.yuyutian.mytools.task.scheduler.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 执行节点健康治理配置。
 *
 * @param offlineAfterSeconds 心跳超时转离线秒数
 * @param scanDelayMs 离线扫描间隔毫秒数
 * @param noAvailableAlertSeconds 无可用节点任务告警宽限秒数
 */
@Validated
@ConfigurationProperties(prefix = "task.node-health")
public record NodeHealthProperties(
        @Min(10) long offlineAfterSeconds,
        @Min(1000) long scanDelayMs,
        @Min(0) long noAvailableAlertSeconds
) {
}
