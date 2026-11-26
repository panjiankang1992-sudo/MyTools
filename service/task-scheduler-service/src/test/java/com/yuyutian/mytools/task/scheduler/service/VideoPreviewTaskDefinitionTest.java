package com.yuyutian.mytools.task.scheduler.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VideoPreviewTaskDefinitionTest {
    @Test
    void shouldMigratePreviewTaskWithRequiredPolicyAndNoModelSteps() {
        String url = "jdbc:h2:mem:preview_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        // 迁移到包含非空策略字段的基线，避开 H2 不支持的无关 MySQL JSON 函数。
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("140").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V170__seed_video_preview_task.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String name = "media_generate_video_preview";
        assertNotNull(jdbc.queryForObject("SELECT child_aggregation_policy_json FROM task_definition WHERE name=?", String.class, name));
        assertEquals(2, jdbc.queryForObject("SELECT max_concurrency FROM task_definition WHERE name=?", Integer.class, name));
        assertEquals(List.of("begin_analysis", "materialize_input", "probe", "generate_thumbnail", "register_thumbnail", "commit_analysis"),
                jdbc.queryForList("SELECT s.name FROM task_step_definition s JOIN task_definition d ON d.id=s.task_definition_id WHERE d.name=? AND s.step_kind='NORMAL' ORDER BY s.sequence_number", String.class, name));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM task_step_definition s JOIN task_definition d ON d.id=s.task_definition_id WHERE d.name=? AND s.step_kind<>'NORMAL'", Integer.class, name));
    }
}
