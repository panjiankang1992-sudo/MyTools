package com.yuyutian.mytools.task.executor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/** 专属执行器的宿主 TLS 配置，配置只保存凭据文件路径，不保存密钥或密码。 */
@ConfigurationProperties("executor.workload-tls")
public record ExecutorWorkloadTlsProperties(@DefaultValue("false") boolean enabled,
                                           @DefaultValue("") String keyStoreFile,
                                           @DefaultValue("") String keyStorePasswordFile,
                                           @DefaultValue("") String trustStoreFile,
                                           @DefaultValue("") String trustStorePasswordFile,
                                           @DefaultValue("") String identity) {
    /** 开启时要求完整配置及规范的工作负载身份。 */
    public ExecutorWorkloadTlsProperties {
        if (enabled) {
            try {
                URI value = URI.create(identity);
                if (keyStoreFile.isBlank() || keyStorePasswordFile.isBlank() || trustStoreFile.isBlank()
                        || trustStorePasswordFile.isBlank() || identity.length() > 512
                        || !"spiffe".equals(value.getScheme()) || value.getHost() == null
                        || value.getPort() != -1 || value.getRawUserInfo() != null || value.getRawQuery() != null
                        || value.getRawFragment() != null || value.getPath().isBlank()
                        || !identity.equals(value.normalize().toASCIIString())) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid executor workload TLS configuration");
            }
        }
    }

    /** 不在诊断中输出凭据路径或主体配置。 */
    @Override
    public String toString() {
        return "ExecutorWorkloadTlsProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
