package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.mapper.RetentionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;

/** 每分钟处理有界批次，积压逐步收敛；多实例共享数据库租约。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="news.retention",name={"enabled","timer-enabled"},havingValue="true")
public class NewsRetentionTimer {
    private final RetentionRepository repository;
    private final Clock clock;
    /** 注入保留策略和统一时钟。 */
    public NewsRetentionTimer(RetentionRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    /** 定时执行一轮，不将失败异常中的数据写入日志。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void sweep(){try{repository.sweep(clock.instant());}catch(com.yuyutian.mytools.news.common.NewsException ignored){/* 失败检查点由管理接口提供，下一轮安全重试。 */}}
}
