package com.yuyutian.mytools.news.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为业务规则注入可替换的统一时钟。 */
@Configuration
public class NewsConfiguration {
    /** 创建协调世界时时钟。 */
    @Bean
    public Clock newsClock() {
        return Clock.systemUTC();
    }
}
