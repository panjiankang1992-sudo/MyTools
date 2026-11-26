package com.yuyutian.mytools.task.scheduler.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 旧部署排队配置的兼容绑定；调度器不再使用此值限制排队。
 *
 * @param queueTimeoutSeconds 兼容旧配置的保留值，不参与排队或执行期限计算
 */
@Validated
@ConfigurationProperties(prefix = "task.scheduler.dispatch")
public record DispatchDeadlineProperties(
        @Min(0) long queueTimeoutSeconds
) {
}
