package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 新版消息副本清理独立于发送开关，默认关闭且限制单轮工作量。 */
@ConfigurationProperties("messaging.outbound.retention")
public record OutboundRetentionProperties(boolean enabled, @DefaultValue("90") int contentDays,
        @DefaultValue("90") int attemptDays, @DefaultValue("250") int batchSize) {
    /** 拒绝无界事务或超出运维保留范围的配置。 */
    public OutboundRetentionProperties {
        if(contentDays<1 || contentDays>365 || attemptDays<1 || attemptDays>365 || batchSize<1 || batchSize>500)
            throw new IllegalArgumentException("Invalid outbound retention policy");
    }
}
