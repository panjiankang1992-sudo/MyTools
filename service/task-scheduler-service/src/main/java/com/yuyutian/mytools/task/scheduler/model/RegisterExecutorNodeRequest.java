package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * 注册执行节点请求。
 *
 * @param name 节点名称
 * @param instanceId 启动实例标识
 * @param capabilities 能力
 * @param labels 标签
 * @param maxConcurrentTasks 最大并发数
 */
public record RegisterExecutorNodeRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]{0,127}$") String name,
        @NotBlank String instanceId,
        @NotNull Map<String, Object> capabilities,
        @NotNull Map<String, Object> labels,
        @Min(1) int maxConcurrentTasks
) {
}
