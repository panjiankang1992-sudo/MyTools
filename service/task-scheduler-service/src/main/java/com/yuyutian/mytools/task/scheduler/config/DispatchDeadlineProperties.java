package com.yuyutian.mytools.task.scheduler.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 首次派发截止配置。
 *
 * @param queueTimeoutSeconds 新任务允许等待首次领取的最长秒数
 */
@Validated
@ConfigurationProperties(prefix = "task.scheduler.dispatch")
public record DispatchDeadlineProperties(
        @Min(30) long queueTimeoutSeconds
) {
}
