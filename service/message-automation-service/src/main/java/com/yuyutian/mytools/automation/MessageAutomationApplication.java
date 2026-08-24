package com.yuyutian.mytools.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 消息自动化服务启动入口。
 */
@SpringBootApplication
@EnableScheduling
public class MessageAutomationApplication {

    /**
     * 启动消息自动化服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(MessageAutomationApplication.class, args);
    }
}
