package com.yuyutian.mytools.messaging.config;

import com.yuyutian.mytools.messaging.service.TaskSchedulerClient;
import com.yuyutian.mytools.messaging.service.DownloadIngestionClient;
import com.yuyutian.mytools.messaging.service.ProviderFileResolverClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 消息服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties({MessagingProperties.class, EmailIngressProperties.class})
public class MessagingConfiguration {

    /**
     * 创建任务调度客户端。
     */
    @Bean
    public TaskSchedulerClient taskSchedulerClient(RestClient.Builder builder, MessagingProperties properties) {
        return new TaskSchedulerClient(builder.baseUrl(properties.schedulerUrl()).build());
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
        return new ProviderFileResolverClient(builder.baseUrl(properties.providerResolverUrl()).build(),
                properties.providerResolverToken());
    }
}
