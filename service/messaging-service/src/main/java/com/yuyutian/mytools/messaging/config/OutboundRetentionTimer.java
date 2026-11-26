package com.yuyutian.mytools.messaging.config;

import com.yuyutian.mytools.messaging.service.OutboundRetentionService;
import com.yuyutian.mytools.messaging.service.OutboundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;

/** 新版正文清理默认关闭，不依赖旧版消息定时器。 */
@Configuration
@ConditionalOnProperty(prefix="messaging.outbound.retention",name={"enabled","timer-enabled"},havingValue="true")
public class OutboundRetentionTimer {
    private final OutboundRetentionService service;
    /** 注入有界保留清理。 */
    public OutboundRetentionTimer(OutboundRetentionService service){this.service=service;}
    /** 每分钟处理一批，失败通过持久检查点查询。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void sweep(){try{service.sweep(Instant.now());}catch(OutboundException ignored){/* 下一轮继续按原状态恢复。 */}}
}
