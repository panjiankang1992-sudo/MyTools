package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 改编派发与恢复独立于创建开关，暂停新建时不能停止已存在任务的清理。 */
@ConfigurationProperties(prefix = "reader.adaptation-dispatch")
public record ReaderAdaptationDispatchProperties(@DefaultValue("false") boolean enabled,
                                                 @DefaultValue("30") int leaseSeconds,
                                                 @DefaultValue("5") int maximumAttempts,
                                                 @DefaultValue("8") int requestTimeoutSeconds,
                                                 @DefaultValue("5") int observeSeconds) {
    /** 确保单次完整 HTTP 预算小于领取租约，限制重试和后台查询频率。 */
    public ReaderAdaptationDispatchProperties {
        if (leaseSeconds < 15 || leaseSeconds > 120 || maximumAttempts < 1 || maximumAttempts > 10
                || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 30 || leaseSeconds < requestTimeoutSeconds + 5
                || observeSeconds < 2 || observeSeconds > 30) {
            throw new IllegalArgumentException("Invalid adaptation dispatch limits");
        }
    }
}
