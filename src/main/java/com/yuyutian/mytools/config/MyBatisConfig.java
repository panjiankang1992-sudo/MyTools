package com.yuyutian.mytools.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Mapper扫描配置。
 * 扫描所有mapper包下的MyBatis Mapper接口。
 * 在测试环境禁用，测试使用@MockBean提供模拟mapper。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Configuration
@MapperScan("com.yuyutian.mytools.**.mapper")
@ConditionalOnProperty(name = "mybatis.mapper-scan.enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisConfig {
}