package com.yuyutian.mytools.task.scheduler.controller;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 新版投递种子在真实旧表结构上验证，不能等价为 MySQL 全量迁移验收。 */
class MessageOutboundTaskMigrationTest {
    @Test void seedsDisabledTaskAndPinsImmutablePackageOnExistingSchema() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:outbound-seed-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).target("140").load().migrate();
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V176__seed_verified_outbound_task.sql"));
        }
        var jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name = 'message_send_channel_message'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package = 'message_send_channel_message'", String.class)).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_instance WHERE task_name = 'message_send_channel_message'", Integer.class)).isZero();
    }
}
