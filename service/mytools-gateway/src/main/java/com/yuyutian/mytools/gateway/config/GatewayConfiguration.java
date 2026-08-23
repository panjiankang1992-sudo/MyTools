package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Gateway HTTP 客户端配置。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    /**
     * 创建带有界超时的下游 HTTP 客户端。
     */
    @Bean
    public RestTemplate gatewayRestTemplate(RestTemplateBuilder builder, GatewayProperties properties) {
        return builder.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .setReadTimeout(Duration.ofMillis(properties.readTimeoutMillis())).build();
    }
}
