package com.yuyutian.mytools.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一消息服务启动入口。
 */
@SpringBootApplication
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
