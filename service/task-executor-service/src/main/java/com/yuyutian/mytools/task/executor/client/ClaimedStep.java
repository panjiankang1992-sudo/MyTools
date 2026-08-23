package com.yuyutian.mytools.task.executor.client;

import java.util.List;
import java.util.UUID;

/**
 * 调度服务下发的脚本步骤。
 *
 * @param stepDefinitionId 步骤定义标识
 * @param name 名称
 * @param stepKind 步骤种类
 * @param scriptPackage 脚本包
 * @param scriptVersion 脚本版本
 * @param entrypoint 入口文件
 * @param argumentsTemplate 参数模板
 * @param timeoutSeconds 超时时间
 * @param failurePolicy 失败处理策略
 * @param sequenceNumber 顺序号
 * @param maxAttempts 最大尝试次数
 */
public record ClaimedStep(
        UUID stepDefinitionId,
        String name,
        String stepKind,
        String scriptPackage,
        String scriptVersion,
        String entrypoint,
        List<String> argumentsTemplate,
        long timeoutSeconds,
        String failurePolicy,
        int sequenceNumber,
        int maxAttempts
) {
}
