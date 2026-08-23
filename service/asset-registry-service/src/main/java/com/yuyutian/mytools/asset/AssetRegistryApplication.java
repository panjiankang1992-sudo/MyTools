package com.yuyutian.mytools.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 资产注册服务启动入口。
 */
@SpringBootApplication
public class AssetRegistryApplication {

    /**
     * 启动资产注册服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(AssetRegistryApplication.class, args);
    }
}
