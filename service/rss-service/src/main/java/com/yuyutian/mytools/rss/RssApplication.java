package com.yuyutian.mytools.rss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** RSS 订阅服务独立启动入口。 */
@SpringBootApplication
public class RssApplication {
    /** 启动服务，不依赖根工程或新闻日报流水线。 */
    public static void main(String[] args) { SpringApplication.run(RssApplication.class, args); }
}
