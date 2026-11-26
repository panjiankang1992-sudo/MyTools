package com.yuyutian.mytools.task.scheduler.service;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 为调度行为测试加载真实基础表和授权表，跳过无关的 MySQL 专有 JSON 数据迁移。 */
@TestConfiguration
class QueueDispatchTestSchema {
    @Bean
    FlywayMigrationStrategy queueDispatchMigrationStrategy() {
        return flyway -> {
            var dataSource = flyway.getConfiguration().getDataSource();
            // 不模拟 V141 等业务数据迁移，仅验证任务创建、领取、授权和期限处理。
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("140").load().migrate();
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V145__add_reader_adaptation_dispatch_guard.sql"), new ClassPathResource(
                    "db/migration/V146__add_task_execution_authorization.sql")).execute(dataSource);
        };
    }
}
