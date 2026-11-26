package com.yuyutian.mytools.news.config;

import com.yuyutian.mytools.news.service.NewsDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 默认关闭的持久投递对账触发器。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="news.delivery",name={"enabled","timer-enabled"},havingValue="true")
public class NewsDispatchTimer {
    private final NewsDispatchService service;
    /** 创建对账触发器。 */
    public NewsDispatchTimer(NewsDispatchService service){this.service=service;}
    /** 每五秒恢复有界投递队列。 */
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void relay(){service.relay();}
}
