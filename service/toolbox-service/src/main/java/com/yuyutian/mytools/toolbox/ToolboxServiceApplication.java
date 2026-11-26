package com.yuyutian.mytools.toolbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 公开工具箱门面服务入口：只提供匿名只读 API 与最小事件上报。
 */
@SpringBootApplication
@EnableScheduling
public class ToolboxServiceApplication {

    /**
     * 启动公开门面服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ToolboxServiceApplication.class, args);
    }
}
