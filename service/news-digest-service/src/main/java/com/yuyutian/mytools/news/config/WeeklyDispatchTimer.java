package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.service.WeeklyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 只派发已经被用户创建的任务，不按时间自动产生新闻任务。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="news.weekly",name={"enabled","timer-enabled"},havingValue="true")
public class WeeklyDispatchTimer {
    private final WeeklyService service;
    /** 创建手动任务的恢复派发器。 */
    public WeeklyDispatchTimer(WeeklyService service) { this.service=service; }
    /** 周期对账仅处理持久队列已有记录。 */
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void relay() { service.relay(); }
    /** 清理过期私有报告，不产生新的新闻或分析会话。 */
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void cleanup() { service.cleanup(); }
}
