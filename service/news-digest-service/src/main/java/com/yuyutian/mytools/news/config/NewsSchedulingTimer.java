package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.service.SchedulingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 显式启用后才安装扫描器，多个服务实例由数据库唯一键与租约协调。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "news.scheduling", name = {"enabled", "timer-enabled"}, havingValue = "true")
public class NewsSchedulingTimer {
    private final SchedulingService service;
    /** 创建持久调度触发器。 */
    public NewsSchedulingTimer(SchedulingService service) { this.service = service; }
    /** 每分钟扫描到期订阅，停机恢复由持久游标决定。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void scan() { service.scan(); }
    /** 每十秒进行任务派发和结果对账。 */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void relay() { service.relay(); }
}
