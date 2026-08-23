package com.yuyutian.mytools.messaging.config;

import com.yuyutian.mytools.messaging.service.TaskSchedulerClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 消息服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

    /**
     * 创建任务调度客户端。
     */
    @Bean
    public TaskSchedulerClient taskSchedulerClient(RestClient.Builder builder, MessagingProperties properties) {
        return new TaskSchedulerClient(builder.baseUrl(properties.schedulerUrl()).build());
    }
}
