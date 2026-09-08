package com.yuyutian.mytools.task.scheduler;

import com.yuyutian.mytools.task.scheduler.config.TaskSecurityProperties;
import com.yuyutian.mytools.task.scheduler.config.NodeRegistrationPolicyProperties;
import com.yuyutian.mytools.task.scheduler.config.NodeHealthProperties;
import com.yuyutian.mytools.task.scheduler.config.TaskOutboxProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务调度服务启动类。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({TaskSecurityProperties.class, NodeRegistrationPolicyProperties.class,
        TaskOutboxProperties.class, NodeHealthProperties.class})
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
