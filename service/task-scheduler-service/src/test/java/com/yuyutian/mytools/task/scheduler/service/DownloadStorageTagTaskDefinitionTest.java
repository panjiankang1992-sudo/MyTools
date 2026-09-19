package com.yuyutian.mytools.task.scheduler.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class DownloadStorageTagTaskDefinitionTest {

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAppendTerminalTagStepsToBothStorageDownloadTasks() {
        assertTaskContract(jdbcTemplate, "download_storage_object", 5, 2100,
                List.of("download_asset", "register_asset", "record_result", "generate_tags", "record_tags"));
        assertTaskContract(jdbcTemplate, "download_remote_storage_object", 2, 7500,
                List.of("download_remote_asset", "register_remote_asset", "record_remote_result",
                        "generate_tags", "record_tags"));
    }

    @Test
    void shouldMigrateByNaturalKeyAndPreserveExistingProductionIdentifiers() {
        MigrationFixture fixture = migrateToVersion138();
        String storageDefinitionId = replaceDefinitionId(
                fixture.jdbcTemplate(), "download_storage_object", UUID.randomUUID().toString());
        String remoteDefinitionId = replaceDefinitionId(
                fixture.jdbcTemplate(), "download_remote_storage_object", UUID.randomUUID().toString());
        fixture.jdbcTemplate().update(
                "UPDATE task_definition SET version = 14 WHERE name = 'download_storage_object'");
        fixture.jdbcTemplate().update(
                "UPDATE task_definition SET version = 9 WHERE name = 'download_remote_storage_object'");
        String storageGenerateId = insertLegacyTagStep(
                fixture.jdbcTemplate(), storageDefinitionId, "generate_tags");
        String storageRecordId = insertLegacyTagStep(
                fixture.jdbcTemplate(), storageDefinitionId, "record_tags");
        String remoteGenerateId = insertLegacyTagStep(
                fixture.jdbcTemplate(), remoteDefinitionId, "generate_tags");
        String remoteRecordId = insertLegacyTagStep(
                fixture.jdbcTemplate(), remoteDefinitionId, "record_tags");

        fixture.migrateToVersion139();

        assertTaskContract(fixture.jdbcTemplate(), "download_storage_object", 15, 2100,
                List.of("download_asset", "register_asset", "record_result", "generate_tags", "record_tags"));
        assertTaskContract(fixture.jdbcTemplate(), "download_remote_storage_object", 10, 7500,
                List.of("download_remote_asset", "register_remote_asset", "record_remote_result",
                        "generate_tags", "record_tags"));
        assertEquals(storageDefinitionId, definitionId(fixture.jdbcTemplate(), "download_storage_object"));
        assertEquals(remoteDefinitionId, definitionId(fixture.jdbcTemplate(), "download_remote_storage_object"));
        assertEquals(storageGenerateId, stepId(fixture.jdbcTemplate(), storageDefinitionId, "generate_tags"));
        assertEquals(storageRecordId, stepId(fixture.jdbcTemplate(), storageDefinitionId, "record_tags"));
        assertEquals(remoteGenerateId, stepId(fixture.jdbcTemplate(), remoteDefinitionId, "generate_tags"));
        assertEquals(remoteRecordId, stepId(fixture.jdbcTemplate(), remoteDefinitionId, "record_tags"));
    }

    private MigrationFixture migrateToVersion138() {
        String databaseUrl = "jdbc:h2:file:"
                + temporaryDirectory.resolve("storage-tags").toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("138"))
                .load()
                .migrate();
        return new MigrationFixture(databaseUrl,
                new JdbcTemplate(new DriverManagerDataSource(databaseUrl, "sa", "")));
    }

    private static String replaceDefinitionId(JdbcTemplate template, String taskName, String replacementId) {
        String existingId = definitionId(template, taskName);
        // 测试夹具模拟生产自定义 UUID，并同步全部外键后重新开启约束检查。
        template.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
            }
            try {
                for (String table : List.of(
                        "task_step_definition", "task_instance", "task_schedule_cursor")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE " + table + " SET task_definition_id = ? WHERE task_definition_id = ?")) {
                        statement.setString(1, replacementId);
                        statement.setString(2, existingId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE task_definition SET id = ? WHERE id = ?")) {
                    statement.setString(1, replacementId);
                    statement.setString(2, existingId);
                    statement.executeUpdate();
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
                }
            }
            return null;
        });
        return replacementId;
    }

    private static String insertLegacyTagStep(JdbcTemplate template, String definitionId, String stepName) {
        String stepId = UUID.randomUUID().toString();
        template.update("""
                INSERT INTO task_step_definition (
                    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
                    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
                    created_at,updated_at
                ) VALUES (?,?,?,'Legacy tag step','NORMAL','legacy_package','0.1.0','legacy.py','[]',
                          FALSE,1,'FAIL_TASK',99,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, stepId, definitionId, stepName);
        return stepId;
    }

    private static void assertTaskContract(JdbcTemplate template, String taskName, int expectedVersion,
                                           int expectedTimeout, List<String> expectedSteps) {
        Map<String, Object> definition = template.queryForMap("""
                SELECT id,version,timeout_seconds FROM task_definition WHERE name = ?
                """, taskName);
        assertEquals(expectedVersion, ((Number) definition.get("version")).intValue());
        assertEquals(expectedTimeout, ((Number) definition.get("timeout_seconds")).intValue());
        List<Map<String, Object>> steps = template.queryForList("""
                SELECT name,script_package,script_version,entrypoint,enabled,timeout_seconds,
                       failure_policy,sequence_number,max_attempts
                FROM task_step_definition WHERE task_definition_id = ? ORDER BY sequence_number
                """, definition.get("id"));
        assertEquals(expectedSteps, steps.stream().map(step -> step.get("name").toString()).toList());
        assertTagStep(steps, "generate_tags", "media_generate_tags", 180, "IGNORE", 40, 1);
        assertTagStep(steps, "record_tags", "download_record_tags", 30, "FAIL_TASK", 50, 3);
    }

    private static void assertTagStep(List<Map<String, Object>> steps, String stepName, String packageName,
                                      int timeout, String failurePolicy, int sequence, int attempts) {
        Map<String, Object> step = steps.stream()
                .filter(candidate -> candidate.get("name").toString().equals(stepName))
                .findFirst().orElseThrow();
        assertEquals(packageName, step.get("script_package"));
        assertEquals("1.0.0", step.get("script_version"));
        assertEquals("scripts/main.py", step.get("entrypoint"));
        assertEquals(Boolean.TRUE, step.get("enabled"));
        assertEquals(timeout, ((Number) step.get("timeout_seconds")).intValue());
        assertEquals(failurePolicy, step.get("failure_policy"));
        assertEquals(sequence, ((Number) step.get("sequence_number")).intValue());
        assertEquals(attempts, ((Number) step.get("max_attempts")).intValue());
    }

    private static String definitionId(JdbcTemplate template, String taskName) {
        return template.queryForObject(
                "SELECT id FROM task_definition WHERE name = ?", String.class, taskName);
    }

    private static String stepId(JdbcTemplate template, String definitionId, String stepName) {
        return template.queryForObject("""
                SELECT id FROM task_step_definition WHERE task_definition_id = ? AND name = ?
                """, String.class, definitionId, stepName);
    }

    private record MigrationFixture(String databaseUrl, JdbcTemplate jdbcTemplate) {

        private void migrateToVersion139() {
            Flyway.configure()
                    .dataSource(databaseUrl, "sa", "")
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("139"))
                    .load()
                    .migrate();
        }
    }
}
