package com.yuyutian.mytools.video;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** 视频生成服务入口。 */
@SpringBootApplication
@EnableScheduling
public class VideoGenerationApplication {
    /** 启动独立视频服务。 */
    public static void main(String[] args) { SpringApplication.run(VideoGenerationApplication.class, args); }
}
