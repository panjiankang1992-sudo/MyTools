package com.yuyutian.mytools.messaging.config;

import com.yuyutian.mytools.messaging.service.OutboundException;
import com.yuyutian.mytools.messaging.service.OutboundRetentionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;

/** 明确正文截止独立于发送和普通保留开关，应用运行时持续执行有界清理。 */
@Configuration
public class OutboundDeadlineTimer {
    private final OutboundRetentionService service;
    /** 注入持久租约与独立检查点保护的截止清理。 */
    public OutboundDeadlineTimer(OutboundRetentionService service){this.service=service;}
    /** 每分钟尝试释放一批过期正文，不触发提供方或调度调用。 */
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void sweep(){try{service.sweepDeadlines(Instant.now());}catch(OutboundException ignored){/* 保留失败检查点并在下一轮使用原身份重试。 */}}
}
