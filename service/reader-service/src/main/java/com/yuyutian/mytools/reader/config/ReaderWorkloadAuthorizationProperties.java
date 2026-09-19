package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Set;

/** Reader 内部执行授权配置，与 Gateway 静态服务令牌完全分离。 */
@ConfigurationProperties("reader.workload-authorization")
public record ReaderWorkloadAuthorizationProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String schedulerUrl, @DefaultValue("mytools-task-scheduler") String issuer,
        @DefaultValue("3") int requestTimeoutSeconds, @DefaultValue("300") int jwksCacheSeconds,
        @DefaultValue("") String keyStoreFile, @DefaultValue("") String keyStorePasswordFile,
        @DefaultValue("") String trustStoreFile, @DefaultValue("") String trustStorePasswordFile,
        @DefaultValue("") String identity, Set<String> executorIdentities) {
    /** 开启时要求独立凭据和精确 URI SAN allowlist，关闭时不读取凭据文件。 */
    public ReaderWorkloadAuthorizationProperties {
        executorIdentities = executorIdentities == null ? Set.of() : Set.copyOf(executorIdentities);
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 8 || jwksCacheSeconds < 1 || jwksCacheSeconds > 900
                || issuer == null || !issuer.matches("[A-Za-z0-9_.:-]{1,128}") || executorIdentities.size() > 64) {
            throw invalid();
        }
        if (enabled) {
            try {
                URI address = URI.create(schedulerUrl);
                if (!"https".equals(address.getScheme()) || address.getHost() == null || address.getRawUserInfo() != null
                        || address.getRawQuery() != null || address.getRawFragment() != null
                        || !(address.getPath().isEmpty() || "/".equals(address.getPath()))
                        || keyStoreFile.isBlank() || keyStorePasswordFile.isBlank() || trustStoreFile.isBlank()
                        || trustStorePasswordFile.isBlank() || executorIdentities.isEmpty()) throw invalid();
                requireIdentity(identity);
                executorIdentities.forEach(ReaderWorkloadAuthorizationProperties::requireIdentity);
            } catch (RuntimeException exception) {
                throw invalid();
            }
        }
    }

    private static void requireIdentity(String identity) {
        URI value = URI.create(identity);
        if (identity.length() > 512 || !"spiffe".equals(value.getScheme()) || value.getHost() == null
                || value.getRawUserInfo() != null || value.getPort() != -1 || value.getRawQuery() != null
                || value.getRawFragment() != null || value.getPath().isBlank()
                || !identity.equals(value.normalize().toASCIIString())) throw invalid();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid reader workload authorization configuration"); }

    /** 不在配置诊断中泄漏凭据路径。 */
    @Override
    public String toString() { return "ReaderWorkloadAuthorizationProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
