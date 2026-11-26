package com.yuyutian.mytools.toolbox.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 门面基础配置：配置属性绑定、统一时钟与编程式事务模板。
 */
@Configuration
@EnableConfigurationProperties({ToolboxProperties.class, TranslationProperties.class, AdminProperties.class, CurriculumProperties.class})
public class ToolboxConfig {

    /**
     * 提供统一 UTC 时钟，便于测试注入固定时间。
     *
     * @return UTC 时钟
     */
    @Bean
    public Clock toolboxClock() {
        return Clock.systemUTC();
    }

    /**
     * 提供编程式事务模板，用于逐条事件写入与逐条聚合提交。
     *
     * @param transactionManager Spring 事务管理器
     * @return 事务模板
     */
    @Bean
    public TransactionTemplate toolboxTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
