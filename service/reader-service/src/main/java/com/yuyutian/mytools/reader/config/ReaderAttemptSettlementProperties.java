package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 调用结算专用密钥挂载，不与 Provider、TLS 或目录游标共用密钥。 */
@ConfigurationProperties("reader.attempt-settlement")
public record ReaderAttemptSettlementProperties(String keyringFile) {
    /** 空路径表示尚未配置签发能力，已有 Reader 功能保持可启动。 */
    public ReaderAttemptSettlementProperties {
        keyringFile = keyringFile == null ? "" : keyringFile;
    }

    /** 配置诊断不输出凭据文件路径。 */
    @Override
    public String toString() { return "ReaderAttemptSettlementProperties[keyringFile=REDACTED]"; }
}
