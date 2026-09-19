package com.yuyutian.mytools.automation.config;

import com.yuyutian.mytools.automation.service.DownloadIngestionClient;
import com.yuyutian.mytools.automation.service.MessagingClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 消息自动化服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationConfiguration {

    /**
     * 限制内部 HTTP 调用时长，避免单个下游请求长期占用对账与回执线程。
     */
    @Bean
    public RestClientCustomizer automationRestClientTimeoutCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(15));
            builder.requestFactory(factory);
        };
    }

    /**
     * 创建消息服务客户端。
     */
    @Bean
    public MessagingClient messagingClient(RestClient.Builder builder, AutomationProperties properties) {
        return new MessagingClient(builder.clone().baseUrl(properties.messagingUrl()).build(),
                properties.messagingToken());
    }

    /**
     * 创建下载接入服务客户端。
     */
    @Bean
    public DownloadIngestionClient downloadIngestionClient(RestClient.Builder builder,
                                                            AutomationProperties properties) {
        return new DownloadIngestionClient(builder.clone().baseUrl(properties.downloadUrl()).build(),
                properties.downloadToken());
    }

}
