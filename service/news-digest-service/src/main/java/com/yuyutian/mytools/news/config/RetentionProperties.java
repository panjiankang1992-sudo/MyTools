package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 有界清理默认关闭；元数据窗口覆盖三十日预览及七日趋势。 */
@ConfigurationProperties("news.retention")
public record RetentionProperties(boolean enabled, @DefaultValue("168") int evidenceHours,
        @DefaultValue("90") int metadataDays, @DefaultValue("180") int editionDays,
        @DefaultValue("90") int deliveryDays, @DefaultValue("250") int batchSize) {
    /** 启动时拒绝可能破坏重放时间窗或制造无界事务的参数。 */
    public RetentionProperties {
        if (evidenceHours < 1 || evidenceHours > 168 || metadataDays < 38 || metadataDays > 365
                || editionDays < 1 || editionDays > 730 || deliveryDays < 1 || deliveryDays > 365
                || batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("Invalid news retention policy");
    }
}
