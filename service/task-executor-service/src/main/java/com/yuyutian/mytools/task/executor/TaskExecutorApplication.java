package com.yuyutian.mytools.task.executor;

import com.yuyutian.mytools.task.executor.config.ExecutorDiskProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorCgroupProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorNetworkIsolationProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorResourceLimitProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorLogArchiveProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorWorkloadTlsProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorNovelAdaptationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务执行节点启动类。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ExecutorProperties.class, ExecutorDiskProperties.class, ExecutorCgroupProperties.class,
        ExecutorResourceLimitProperties.class, ExecutorNetworkIsolationProperties.class,
        ExecutorLogArchiveProperties.class, ExecutorWorkloadTlsProperties.class, ExecutorNovelAdaptationProperties.class})
public class TaskExecutorApplication {

    /**
     * 启动任务执行节点。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskExecutorApplication.class, args);
    }
}
