package com.yuyutian.mytools.automation.config;

import com.yuyutian.mytools.automation.service.DownloadIngestionClient;
import com.yuyutian.mytools.automation.service.MessagingClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 消息自动化服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationConfiguration {

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
        return new DownloadIngestionClient(builder.clone().baseUrl(properties.downloadUrl()).build());
    }
}
