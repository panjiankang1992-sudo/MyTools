package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

/** 独立注册 RSS 配置，保持既有领域配置构造协议。 */
@Configuration
@EnableConfigurationProperties(RssGatewayProperties.class)
public class RssGatewayConfiguration {
    /** RSS 发现最多二十秒，独立客户端不改变其他领域的低延迟预算。 */
    @Bean
    public RssHttpClient rssHttpClient(RestTemplateBuilder builder) {
        return new RssHttpClient(builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(25)).build());
    }
    /** 包装独立 HTTP 客户端，避免多个 RestTemplate Bean 干扰原有注入。 */
    public record RssHttpClient(RestTemplate rest) { }
}
