package com.yuyutian.mytools.task.scheduler.controller;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 新增新闻种子在真实旧表结构上验证，不能等价为 MySQL 全量迁移验收。 */
class NewsTaskMigrationTest {
    @Test void weeklySeedDoesNotRequireUnreleasedDailySeeds() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:news-independent-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).target("140").load().migrate();
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V185__seed_manual_weekly_news.sql"));
        }
        var jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM execution_cluster WHERE name='news'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_definition WHERE name='news_generate_edition'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name='news_generate_weekly'", Boolean.class)).isFalse();
    }
    @Test void seedsDisabledTaskAndPinsImmutablePackageOnExistingSchema() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:news-seed-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(data).target("140").load().migrate();
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V173__seed_news_edition_task.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V174__seed_news_region_task.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V185__seed_manual_weekly_news.sql"));
        }
        var jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name='news_generate_weekly'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT cron_expression FROM task_definition WHERE name='news_generate_weekly'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT task_type FROM task_definition WHERE name='news_generate_weekly'",String.class)).isEqualTo("IMMEDIATE");
        assertThat(jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package='news_generate_weekly'",String.class)).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_instance WHERE task_name='news_generate_weekly'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name = 'news_generate_edition'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name = 'news_resolve_region'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM execution_cluster WHERE name = 'news'", Boolean.class)).isFalse();
        var controller = new NewsWeeklyDeploymentController(jdbc);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var enable = new NewsWeeklyDeploymentController.Deployment(true, "weekly_acceptance_01");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.update(enable, request))
                .isInstanceOf(com.yuyutian.mytools.task.scheduler.common.SchedulerException.class);
        request.setAttribute(com.yuyutian.mytools.task.scheduler.config.InternalTokenFilter.AUTHENTICATED_SERVICE_ATTRIBUTE,
                "task-operator-service");
        controller.update(enable, request);
        assertThat(controller.update(enable, request).get("reused")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_weekly_deployment_audit", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name='news_generate_weekly'", Boolean.class)).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.update(
                new NewsWeeklyDeploymentController.Deployment(false, "weekly_acceptance_01"), request))
                .isInstanceOf(com.yuyutian.mytools.task.scheduler.common.SchedulerException.class);
        controller.update(new NewsWeeklyDeploymentController.Deployment(false, "weekly_rollback_01"), request);
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name='news_generate_weekly'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package = 'news_resolve_region'", String.class)).isEqualTo("1.1.0");
        assertThat(jdbc.queryForObject("SELECT enabled FROM execution_cluster WHERE name = 'news'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package = 'news_generate_edition'", String.class)).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_instance WHERE task_name = 'news_generate_edition'", Integer.class)).isZero();
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V177__upgrade_news_region_provider.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package = 'news_resolve_region'", String.class)).isEqualTo("1.2.0");
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name = 'news_resolve_region'", Boolean.class)).isFalse();
    }
}
