package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 采集数据采用持久额度；此额度不代表整个数据库的磁盘上限。 */
@ConfigurationProperties("news.collection-capacity")
public record CollectionCapacityProperties(@DefaultValue("250000") long articleRows,
        @DefaultValue("250000") long snapshotRows, @DefaultValue("100000") long withdrawalRows,
        @DefaultValue("536870912") long payloadBytes) {
    /** 启动时拒绝零额度、负数及无法安全计算的配置。 */
    public CollectionCapacityProperties {
        if (articleRows < 1 || articleRows > 10_000_000 || snapshotRows < 1 || snapshotRows > 10_000_000
                || withdrawalRows < 1 || withdrawalRows > 10_000_000 || payloadBytes < 1
                || payloadBytes > 100_000_000_000L) throw new IllegalArgumentException("Invalid collection capacity");
    }
}
