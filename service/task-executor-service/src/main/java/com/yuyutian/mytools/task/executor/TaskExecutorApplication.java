package com.yuyutian.mytools.task.executor;

import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务执行节点启动类。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ExecutorProperties.class)
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
