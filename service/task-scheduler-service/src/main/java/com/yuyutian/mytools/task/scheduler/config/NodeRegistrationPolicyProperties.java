package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * Executor 节点注册服务端授权策略。
 *
 * @param enforce 是否强制执行服务端授权
 * @param allowedClusterNames 允许节点自动加入的集群名称
 * @param trustedLabels 由 Scheduler 赋值的可信标签
 * @param allowedCapabilityKeys 允许节点声明的能力键
 */
@ConfigurationProperties(prefix = "task.node-registration")
public record NodeRegistrationPolicyProperties(
        boolean enforce,
        Set<String> allowedClusterNames,
        Map<String, Object> trustedLabels,
        Set<String> allowedCapabilityKeys
) {
    /**
     * 补齐可选集合配置的空值。
     */
    public NodeRegistrationPolicyProperties {
        allowedClusterNames = allowedClusterNames == null ? Set.of() : Set.copyOf(allowedClusterNames);
        trustedLabels = trustedLabels == null ? Map.of() : Map.copyOf(trustedLabels);
        allowedCapabilityKeys = allowedCapabilityKeys == null ? Set.of() : Set.copyOf(allowedCapabilityKeys);
    }
}
