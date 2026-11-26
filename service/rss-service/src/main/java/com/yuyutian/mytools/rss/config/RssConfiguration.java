package com.yuyutian.mytools.rss.config;

import com.yuyutian.mytools.feed.FeedParser;
import com.yuyutian.mytools.feed.FeedTransport;
import com.yuyutian.mytools.feed.PublicFeedTransport;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 注册可替换时钟与传输器，测试无需放开公网校验。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RssProperties.class)
public class RssConfiguration {
    /** 使用 UTC 存储真实收录时间。 */
    @Bean
    public Clock rssClock() { return Clock.systemUTC(); }
    /** 提供无凭据公网抓取器。 */
    @Bean
    public FeedTransport feedTransport() { return new PublicFeedTransport(); }
    /** 提供安全的图文解析器。 */
    @Bean
    public FeedParser feedParser() { return new FeedParser(); }
}
