package com.yuyutian.mytools.config;

import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花算法ID生成器配置类。
 * 根据配置创建SnowflakeIdGenerator实例。
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "snowflake.enabled", havingValue = "true", matchIfMissing = true)
public class SnowflakeConfig {

    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;

    @Value("${snowflake.worker-id:1}")
    private long workerId;

    /**
     * 创建雪花算法ID生成器实例。
     *
     * @return SnowflakeIdGenerator实例
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        log.info("Initializing Snowflake ID generator: datacenterId={}, workerId={}", datacenterId, workerId);
        return new SnowflakeIdGenerator(datacenterId, workerId);
    }
}
