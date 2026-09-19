package com.yuyutian.mytools.task.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** 资源执行授权独立开关；受信身份来自 TLS URI SAN，节点名称由服务端配置绑定。 */
@ConfigurationProperties(prefix = "task.workload-authorization")
public record WorkloadAuthorizationProperties(@DefaultValue("false") boolean enabled,
                                               @DefaultValue("") String signingKeyringFile,
                                               @DefaultValue("mytools-task-scheduler") String issuer,
                                               @DefaultValue("60") int assertionSeconds,
                                               @DefaultValue("5") int overlapSeconds,
                                               Map<String, String> executorIdentities,
                                               Set<String> readerIdentities) {
    /** 校验有界授权窗口及工作负载身份，不接受宽泛 SAN 前缀匹配。 */
    public WorkloadAuthorizationProperties {
        executorIdentities = executorIdentities == null ? Map.of() : Map.copyOf(executorIdentities);
        readerIdentities = readerIdentities == null ? Set.of() : Set.copyOf(readerIdentities);
        if (issuer == null || !issuer.matches("[A-Za-z0-9:_./-]{1,128}") || assertionSeconds < 10 || assertionSeconds > 120
                || overlapSeconds < 0 || overlapSeconds > 5 || executorIdentities.size() > 64 || readerIdentities.size() > 16
                || executorIdentities.keySet().stream().anyMatch(name -> !name.matches("[A-Za-z0-9_.-]{1,128}"))) {
            throw new IllegalArgumentException("Invalid workload authorization configuration");
        }
        for (String identity : executorIdentities.values()) {
            requireIdentity(identity);
        }
        for (String identity : readerIdentities) {
            requireIdentity(identity);
        }
        if (enabled && (signingKeyringFile == null || signingKeyringFile.isBlank() || executorIdentities.isEmpty() || readerIdentities.isEmpty())) {
            throw new IllegalArgumentException("Workload authorization requires explicit identity and signing configuration");
        }
    }

    private static void requireIdentity(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 512 || !"spiffe".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().isBlank() || !value.equals(uri.toASCIIString())
                    || !value.equals(uri.normalize().toString())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid workload identity");
        }
    }
}
