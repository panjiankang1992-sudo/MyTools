package com.yuyutian.mytools.image;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** 图片生成服务入口。 */
@SpringBootApplication
@EnableScheduling
public class ImageGenerationApplication {
    /** 启动独立图片服务。 */
    public static void main(String[] args) { SpringApplication.run(ImageGenerationApplication.class, args); }
}
