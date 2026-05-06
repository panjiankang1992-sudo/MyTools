package com.yuyutian.mytools.config;

import org.springframework.context.annotation.Configuration;

/**
 * 多数据源配置类。
 * 使用baomidou dynamic-datasource实现多数据源自动配置。
 * 数据源配置在application.yml中通过spring.datasource.dynamic.datasource配置。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Configuration
public class DataSourceConfig {
    // dynamic-datasource会自动读取application.yml中的配置
    // primary = my_tools（主数据源）
    // secondary = sales_order（辅助数据源）
    // 使用@DS注解在Mapper层进行数据源路由
}
