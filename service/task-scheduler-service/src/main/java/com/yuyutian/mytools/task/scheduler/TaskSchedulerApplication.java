package com.yuyutian.mytools.task.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度服务启动类。
 */
@SpringBootApplication
@EnableScheduling
public class TaskSchedulerApplication {

    /**
     * 启动任务调度服务。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TaskSchedulerApplication.class, args);
    }
}
