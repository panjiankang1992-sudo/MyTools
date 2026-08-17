package com.yuyutian.mytools.localfile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 本地文件扫描线程池配置。
 */
@Configuration
public class LocalFileScanConfig {

    /**
     * 创建本地文件扫描专用线程池。
     *
     * @return 扫描任务执行器
     */
    @Bean("localFileScanExecutor")
    public Executor localFileScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("local-file-scan-");
        executor.initialize();
        return executor;
    }
}
