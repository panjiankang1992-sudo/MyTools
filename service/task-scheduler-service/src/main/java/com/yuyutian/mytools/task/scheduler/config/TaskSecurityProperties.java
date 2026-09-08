package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务调度内部接口安全配置。
 *
 * @param required 是否强制校验内部令牌
 * @param internalToken Executor 调用内部接口时使用的共享令牌
 * @param businessToken 业务服务调用任务实例接口时使用的共享令牌
 * @param internalClients 内部服务身份与独立令牌映射
 * @param businessClients 业务服务身份与独立令牌映射
 */
@ConfigurationProperties(prefix = "task.security")
public record TaskSecurityProperties(boolean required, String internalToken, String businessToken,
                                     Map<String, String> internalClients,
                                     Map<String, String> businessClients) {

    /**
     * 规范化独立服务凭据，忽略尚未配置的空令牌。
     */
    @ConstructorBinding
    public TaskSecurityProperties {
        internalClients = normalizedClients(internalClients);
        businessClients = normalizedClients(businessClients);
    }

    /**
     * 创建仅使用共享令牌的兼容配置。
     *
     * @param required 是否强制鉴权
     * @param internalToken 内部共享令牌
     * @param businessToken 业务共享令牌
     */
    public TaskSecurityProperties(boolean required, String internalToken, String businessToken) {
        this(required, internalToken, businessToken, Map.of(), Map.of());
    }

    private static Map<String, String> normalizedClients(Map<String, String> clients) {
        if (clients == null || clients.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        clients.forEach((serviceId, token) -> {
            if (token == null || token.isBlank()) {
                return;
            }
            if (serviceId == null || !serviceId.matches("^[a-z][a-z0-9-]{1,63}$")) {
                throw new IllegalArgumentException("Task security service identifier is invalid");
            }
            if (normalized.containsValue(token)) {
                throw new IllegalArgumentException("Task security service tokens must be unique");
            }
            normalized.put(serviceId, token);
        });
        return Map.copyOf(normalized);
    }
}
