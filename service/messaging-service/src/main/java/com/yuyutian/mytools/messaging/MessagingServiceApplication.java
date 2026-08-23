package com.yuyutian.mytools.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 统一消息服务启动入口。
 */
@SpringBootApplication
@EnableScheduling
public class MessagingServiceApplication {

    /**
     * 启动消息服务。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MessagingServiceApplication.class, args);
    }
}
