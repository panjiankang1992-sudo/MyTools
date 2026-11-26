package com.yuyutian.mytools.pdf;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** PDF 工具独立服务入口。 */
@SpringBootApplication
@EnableScheduling
public class PdfApplication {
    /** 启动领域接口与任务对账。 */
    public static void main(String[] args) { SpringApplication.run(PdfApplication.class, args); }
}
