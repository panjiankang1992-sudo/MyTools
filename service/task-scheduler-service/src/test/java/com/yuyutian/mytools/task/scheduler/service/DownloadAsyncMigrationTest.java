package com.yuyutian.mytools.task.scheduler.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证异步下载迁移满足真实基础表的非空约束，并保留未改造任务。 */
class DownloadAsyncMigrationTest {
    @Test
    void migrationUsesCurrentConstraintsAndSeparatesEnrichment() {
        var flyway = Flyway.configure().dataSource("jdbc:h2:mem:async-download-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration").target("140").load();
        flyway.migrate();
        var dataSource = flyway.getConfiguration().getDataSource();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V180__detach_download_enrichment.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        assertNotNull(jdbc.queryForObject("SELECT child_aggregation_policy_json FROM task_definition "
                + "WHERE name='download_enrich_file'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT max_concurrent_tasks FROM execution_cluster "
                + "WHERE name='download-enrichment'", Integer.class));
        assertEquals("1.1.0", jdbc.queryForObject("SELECT script_version FROM task_step_definition s "
                + "JOIN task_definition d ON d.id=s.task_definition_id "
                + "WHERE d.name='download_message_attachment' AND s.name='record_result'", String.class));
        assertEquals("1.0.0", jdbc.queryForObject("SELECT script_version FROM task_step_definition s "
                + "JOIN task_definition d ON d.id=s.task_definition_id "
                + "WHERE d.name='download_publish_text' AND s.name='record_result'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_step_definition s "
                + "JOIN task_definition d ON d.id=s.task_definition_id WHERE d.name='download_http_asset' "
                + "AND s.name IN ('generate_tags','record_tags','generate_thumbnail') AND s.enabled=TRUE", Integer.class));
        assertEquals("1.2.1", jdbc.queryForObject("SELECT script_version FROM task_step_definition "
                + "WHERE script_package='message_submit_attachment_download'", String.class));
    }
}
