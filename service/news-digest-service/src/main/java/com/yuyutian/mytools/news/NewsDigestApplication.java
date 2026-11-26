package com.yuyutian.mytools.news;

import com.yuyutian.mytools.news.config.NewsProperties;
import com.yuyutian.mytools.news.config.TechSourceProperties;
import com.yuyutian.mytools.news.config.RegionProperties;
import com.yuyutian.mytools.news.config.SchedulingProperties;
import com.yuyutian.mytools.news.config.PipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 新闻速报服务入口。 */
@SpringBootApplication
@EnableConfigurationProperties({com.yuyutian.mytools.news.config.WeeklyProperties.class, NewsProperties.class, TechSourceProperties.class, RegionProperties.class, SchedulingProperties.class, PipelineProperties.class, com.yuyutian.mytools.news.config.DeliveryProperties.class, com.yuyutian.mytools.news.config.RetentionProperties.class, com.yuyutian.mytools.news.config.CollectionCapacityProperties.class, com.yuyutian.mytools.news.config.ControlCapacityProperties.class, com.yuyutian.mytools.news.config.RegistryCapacityProperties.class})
public class NewsDigestApplication {
    /** 启动独立新闻服务。 */
    public static void main(String[] args) {
        SpringApplication.run(NewsDigestApplication.class, args);
    }
}
