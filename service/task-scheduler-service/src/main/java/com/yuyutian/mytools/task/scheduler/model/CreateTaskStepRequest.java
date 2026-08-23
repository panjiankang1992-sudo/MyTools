package com.yuyutian.mytools.task.scheduler.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * 创建任务步骤请求。
 *
 * @param name 名称
 * @param description 描述
 * @param stepKind 步骤种类
 * @param scriptPackage 脚本包
 * @param scriptVersion 脚本版本
 * @param entrypoint 入口文件
 * @param argumentsTemplate 参数模板
 * @param enabled 是否启用
 * @param timeoutSeconds 超时时间
 * @param failurePolicy 失败策略
 * @param sequenceNumber 顺序号
 * @param maxAttempts 最大尝试次数
 */
public record CreateTaskStepRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,127}$") String name,
        String description,
        @NotNull StepKind stepKind,
        @NotBlank String scriptPackage,
        @NotBlank String scriptVersion,
        @NotBlank String entrypoint,
        @NotNull List<String> argumentsTemplate,
        boolean enabled,
        @Min(1) long timeoutSeconds,
        @NotNull FailurePolicy failurePolicy,
        @Min(0) int sequenceNumber,
        @Min(1) int maxAttempts
) {
}
