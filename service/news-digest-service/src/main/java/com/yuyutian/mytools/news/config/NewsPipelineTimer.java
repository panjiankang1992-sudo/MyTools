package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.service.RegionJobService;
import com.yuyutian.mytools.news.service.SourcePollingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** 持久游标和租约协调多个实例，开关默认关闭。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "news.pipeline", name = {"enabled", "timer-enabled"}, havingValue = "true")
public class NewsPipelineTimer {
    private final SourcePollingService sources;
    private final RegionJobService regions;
    /** 创建周期触发器，来源慢请求不阻塞地区对账。 */
    public NewsPipelineTimer(SourcePollingService sources, RegionJobService regions) { this.sources = sources; this.regions = regions; }
    /** 每分钟启动有界来源轮询。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 15000)
    public void poll() { sources.poll(); }
    /** 每分钟检查当前地区桶。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 1000)
    public void scan() { regions.scan(); }
    /** 每十秒推进地区任务出站与对账。 */
    @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    public void relay() { regions.relay(); }
}
