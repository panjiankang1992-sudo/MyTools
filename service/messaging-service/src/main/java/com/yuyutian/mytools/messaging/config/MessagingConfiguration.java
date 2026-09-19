package com.yuyutian.mytools.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.service.TaskSchedulerClient;
import com.yuyutian.mytools.messaging.service.DownloadIngestionClient;
import com.yuyutian.mytools.messaging.service.ProviderFileResolverClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 消息服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties({MessagingProperties.class, EmailIngressProperties.class})
public class MessagingConfiguration {

    /**
     * 限制内部 HTTP 调用时长，避免单个慢请求阻塞后续入站消息和回复。
     */
    @Bean
    public RestClientCustomizer messagingRestClientTimeoutCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(15));
            builder.requestFactory(factory);
        };
    }

    /**
     * 创建任务调度客户端。
     */
    @Bean
    public TaskSchedulerClient taskSchedulerClient(RestClient.Builder builder, MessagingProperties properties,
                                                   @Value("${messaging.scheduler-token:}") String schedulerToken,
                                                   ObjectMapper objectMapper) {
        return new TaskSchedulerClient(new com.yuyutian.mytools.task.client.TaskSchedulerClient(
                builder.baseUrl(properties.schedulerUrl()).build(), objectMapper, schedulerToken,
                "messaging-service"));
    }

    /**
     * 创建下载接入服务客户端。
     */
    @Bean
    public DownloadIngestionClient downloadIngestionClient(RestClient.Builder builder,
                                                           MessagingProperties properties) {
        return new DownloadIngestionClient(builder.baseUrl(properties.downloadIngestionUrl()).build(),
                properties.downloadIngestionToken());
    }

    /**
     * 创建渠道文件解析客户端。
     */
    @Bean
    public ProviderFileResolverClient providerFileResolverClient(RestClient.Builder builder,
                                                                 MessagingProperties properties) {
        return new ProviderFileResolverClient(
                builder.clone().baseUrl(properties.providerResolverUrl()).build(),
                properties.providerResolverToken(),
                builder.clone().baseUrl(properties.telegramConnectorUrl()).build(),
                properties.telegramConnectorToken());
    }
}
