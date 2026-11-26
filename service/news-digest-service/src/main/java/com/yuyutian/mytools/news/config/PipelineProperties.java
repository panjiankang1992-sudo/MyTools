package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 周期采集与地区调度默认关闭，首次启用不回补过去时间桶。 */
@ConfigurationProperties("news.pipeline")
public record PipelineProperties(@DefaultValue("false") boolean enabled,
                                 @DefaultValue("30") int rssIntervalMinutes) {
    /** 限定 RSS 的最小采样预算。 */
    public PipelineProperties {
        if (rssIntervalMinutes < 5 || rssIntervalMinutes > 1440) throw new IllegalArgumentException("Invalid RSS polling interval");
    }
}
