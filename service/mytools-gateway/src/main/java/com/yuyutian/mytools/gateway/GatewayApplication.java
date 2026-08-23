package com.yuyutian.mytools.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MyTools 统一入口应用。
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * 启动 Gateway。
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
