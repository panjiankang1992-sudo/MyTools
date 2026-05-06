package com.yuyutian.mytools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 应用主入口类。
 * 负责启动整个应用程序上下文。
 *
 * @author mytools
 * @since 2026-04-22
 */
@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
public class MyToolsApplication {

    /**
     * 应用启动入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MyToolsApplication.class, args);
    }
}
