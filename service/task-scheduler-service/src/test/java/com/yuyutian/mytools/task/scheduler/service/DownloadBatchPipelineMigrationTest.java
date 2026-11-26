package com.yuyutian.mytools.task.scheduler.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 验证消息入口容量隔离及脚本升级不会放大站点并发。 */
class DownloadBatchPipelineMigrationTest {
    @Test
    void pipelineSeparatesParentsAndPreservesProviderLimits() {
        var flyway = Flyway.configure().dataSource("jdbc:h2:mem:batch-pipeline-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration").target("140").load();
        flyway.migrate();
        var source = flyway.getConfiguration().getDataSource();
        var jdbc = new JdbcTemplate(source);
        // 模拟此前已经发布的用户时间线版本，再验证本次前向迁移。
        jdbc.update("UPDATE task_step_definition SET script_version='1.1.1' "
                + "WHERE script_package='download_resolve_x_user'");
        var previousCluster = jdbc.queryForObject("SELECT cluster_id FROM task_definition "
                + "WHERE name='download_message_url_batch'", String.class);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V181__pipeline_download_batches.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V182__align_batch_execution_capacity.sql")).execute(source);
        var previousVersion = jdbc.queryForObject("SELECT version FROM task_definition "
                + "WHERE name='download_message_url_batch'", Integer.class);
        var childTimeout = jdbc.queryForObject("SELECT timeout_seconds FROM task_definition "
                + "WHERE name='download_resolve_x_url'", Integer.class);
        var removeChildWaitDeadlines = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V183__remove_message_batch_child_wait_deadlines.sql"));
        // 重复执行仍只升级一次编排版本，子任务执行预算保持原值。
        removeChildWaitDeadlines.execute(source);
        removeChildWaitDeadlines.execute(source);
        assertEquals(previousVersion + 1, jdbc.queryForObject("SELECT version FROM task_definition "
                + "WHERE name='download_message_url_batch'", Integer.class));
        assertEquals(childTimeout, jdbc.queryForObject("SELECT timeout_seconds FROM task_definition "
                + "WHERE name='download_resolve_x_url'", Integer.class));
        assertEquals(90000, jdbc.queryForObject("SELECT timeout_seconds FROM task_definition "
                + "WHERE name='download_message_url_batch'", Integer.class));
        assertEquals(90000, jdbc.queryForObject("SELECT timeout_seconds FROM task_step_definition "
                + "WHERE script_package='download_message_url_batch'", Integer.class));
        assertEquals(86400, jdbc.queryForObject("SELECT timeout_seconds FROM task_definition "
                + "WHERE name='download_x_user'", Integer.class));
        var batchCluster = jdbc.queryForObject("SELECT cluster_id FROM task_definition "
                + "WHERE name='download_message_url_batch'", String.class);
        assertNotEquals(previousCluster, batchCluster);
        assertEquals(2 + TaskOrchestrationCatalog.maximumDescendantDepth("download_message_url_batch",
                java.util.List.of("download_message_url_batch")),
                jdbc.queryForObject("SELECT max_concurrent_tasks FROM execution_cluster WHERE id=?",
                Integer.class, batchCluster));
        assertEquals(2, jdbc.queryForObject("SELECT max_concurrency FROM task_definition "
                + "WHERE name='download_message_url_batch'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT max_concurrency FROM task_definition "
                + "WHERE name='download_x_user'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT max_concurrency FROM task_definition "
                + "WHERE name='download_x_post'", Integer.class));
        assertEquals(previousCluster, jdbc.queryForObject("SELECT cluster_id FROM task_definition "
                + "WHERE name='download_x_user'", String.class));
        assertEquals("1.2.0", jdbc.queryForObject("SELECT script_version FROM task_step_definition "
                + "WHERE script_package='download_message_url_batch'", String.class));
        assertEquals("1.2.0", jdbc.queryForObject("SELECT script_version FROM task_step_definition "
                + "WHERE script_package='download_resolve_x_user'", String.class));
    }
}
