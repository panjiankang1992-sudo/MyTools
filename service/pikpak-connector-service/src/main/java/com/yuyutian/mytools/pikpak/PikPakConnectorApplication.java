package com.yuyutian.mytools.pikpak;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** PikPak 连接器服务入口。 */
@SpringBootApplication
public class PikPakConnectorApplication {
    /** 启动 PikPak 连接器。 @param args 启动参数 */
    public static void main(String[] args) {
        SpringApplication.run(PikPakConnectorApplication.class, args);
    }
}
