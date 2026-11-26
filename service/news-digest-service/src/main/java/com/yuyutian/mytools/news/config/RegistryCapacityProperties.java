package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 来源与地区登记始终使用有限额度，已接纳地区任务同时预留一个报告名额。 */
@ConfigurationProperties("news.registry-capacity")
public record RegistryCapacityProperties(@DefaultValue("1000") long sources,
        @DefaultValue("10000") long sourcePacks,@DefaultValue("1000") long regionNodes,
        @DefaultValue("100000") long regionJobs,@DefaultValue("10000") long directObservations) {
    /** 拒绝零额度及超出受支持范围的配置。 */
    public RegistryCapacityProperties {
        // 来源、节点与长期任务分别受限，不能用零值表示无限容量。
        if(sources<1 || sources>100000 || sourcePacks<1 || sourcePacks>1000000 || regionNodes<1 || regionNodes>100000
                || regionJobs<1 || regionJobs>10000000 || directObservations<1 || directObservations>1000000)
            throw new IllegalArgumentException("Invalid news registry capacity");
    }
}
