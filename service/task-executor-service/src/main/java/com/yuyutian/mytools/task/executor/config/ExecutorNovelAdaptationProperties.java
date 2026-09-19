package com.yuyutian.mytools.task.executor.config;

import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 改编宿主和恢复分别开关；默认均关闭，凭据仅以文件路径配置。 */
@ConfigurationProperties("executor.novel-adaptation")
public record ExecutorNovelAdaptationProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean recoveryEnabled, @DefaultValue("") String readerUrl,
        @DefaultValue("") String providerDeploymentId, @DefaultValue("") String modelId,
        @DefaultValue("1") long credentialGeneration, @DefaultValue("") String providerCredentialFile,
        @DefaultValue("Authorization") String providerHeader, @DefaultValue("Bearer") String providerPrefix,
        @DefaultValue("1048576") int maximumRequestBytes, @DefaultValue("8192") int maximumOutputTokens,
        @DefaultValue("false") boolean acceptSse, @DefaultValue("") String relayRoot, @DefaultValue("") String relayKeyFile,
        @DefaultValue("") String brokerRoot, @DefaultValue("/usr/bin/bwrap") String sandboxExecutable,
        @DefaultValue("") String sandboxRoot, @DefaultValue("false") boolean isolationVerified,
        @DefaultValue("false") boolean providerContentCompatibility,
        @DefaultValue("false") boolean providerQuoteProtocol,
        @DefaultValue("false") boolean providerInsertionProtocol) {
    /** 防止创建开关绕过独立恢复能力和部署隔离验收。 */
    public ExecutorNovelAdaptationProperties {
        if (enabled && (!recoveryEnabled || !isolationVerified)) throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
        if (providerQuoteProtocol && !providerContentCompatibility) throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
        if (providerInsertionProtocol && !providerQuoteProtocol) throw new ReaderAdaptationException(ErrorCode.DISABLED, 0);
    }
    /** 配置诊断不输出文件路径、认证信息或模型部署身份。 */
    @Override public String toString() { return "ExecutorNovelAdaptationProperties[enabled=" + enabled + ", recoveryEnabled=" + recoveryEnabled + ", configuration=REDACTED]"; }
}
