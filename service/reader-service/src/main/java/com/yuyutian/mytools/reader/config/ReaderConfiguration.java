package com.yuyutian.mytools.reader.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.service.TaskSchedulerClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 阅读服务依赖配置。
 */
@Configuration
@EnableConfigurationProperties({ReaderProperties.class, ReaderChapterContentProperties.class,
        ReaderShelfChapterProperties.class, ReaderAdaptationProperties.class, ReaderAdaptationDispatchProperties.class,
        ReaderWorkloadAuthorizationProperties.class, ReaderAttemptSettlementProperties.class, ReaderStoryConstraintProperties.class})
public class ReaderConfiguration {

    /**
     * 创建任务调度客户端。
     *
     * @param builder HTTP 客户端构建器
     * @param properties 阅读服务配置
     * @param objectMapper JSON 转换器
     * @return 任务调度客户端
     */
    @Bean
    public TaskSchedulerClient taskSchedulerClient(RestClient.Builder builder, ReaderProperties properties,
                                                   ObjectMapper objectMapper,
                                                   @Value("${reader.scheduler-token:}") String schedulerToken) {
        return new TaskSchedulerClient(new com.yuyutian.mytools.task.client.TaskSchedulerClient(
                builder.baseUrl(properties.schedulerUrl()).build(), objectMapper, schedulerToken,
                "reader-service"), objectMapper);
    }
}
