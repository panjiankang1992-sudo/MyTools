package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 为长期保留的订阅、运行和内容身份配置全局行额度。 */
@ConfigurationProperties("news.control-capacity")
public record ControlCapacityProperties(@DefaultValue("1000") long subscriptions,
        @DefaultValue("1000") long targetOwners, @DefaultValue("100000") long dailyRuns,
        @DefaultValue("100000") long previewIdentities) {
    /** 拒绝无效或超出受支持范围的行数配置。 */
    public ControlCapacityProperties {
        if (subscriptions<1 || subscriptions>100000 || targetOwners<1 || targetOwners>100000
                || dailyRuns<1 || dailyRuns>10000000 || previewIdentities<1 || previewIdentities>10000000) {
            throw new IllegalArgumentException("Invalid control capacity");
        }
    }
}
