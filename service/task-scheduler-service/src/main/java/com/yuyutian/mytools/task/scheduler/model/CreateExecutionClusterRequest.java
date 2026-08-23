package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * 创建执行集群请求。
 *
 * @param name 名称
 * @param description 描述
 * @param dispatchStrategy 调度策略
 * @param maxConcurrentTasks 最大并发数
 * @param labels 标签
 * @param enabled 是否启用
 */
public record CreateExecutionClusterRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,127}$") String name,
        String description,
        @NotBlank String dispatchStrategy,
        @Min(1) int maxConcurrentTasks,
        @NotNull Map<String, Object> labels,
        boolean enabled
) {
}
