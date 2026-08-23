package com.yuyutian.mytools.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 存储网关服务入口。
 */
@SpringBootApplication
public class StorageGatewayApplication {

    /**
     * 启动存储网关。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(StorageGatewayApplication.class, args);
    }
}
