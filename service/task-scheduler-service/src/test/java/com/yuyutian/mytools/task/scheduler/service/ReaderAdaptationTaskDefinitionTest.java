package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReaderAdaptationTaskDefinitionTest {
    private static final String TASK = "reader_adapt_novel_chapter";
    private static JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TaskSchemaValidationService schemas = new TaskSchemaValidationService(JSON);

    @BeforeAll
    static void migrateFixture() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:adaptation-definition-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        // V141–V144 含 MySQL 专有 JSON_SET，此定向夹具明确不模拟或宣称验证这些无关迁移。
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("140").load().migrate();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V145__add_reader_adaptation_dispatch_guard.sql"),
                new ClassPathResource("db/migration/V146__add_task_execution_authorization.sql"),
                new ClassPathResource("db/migration/V147__seed_reader_chapter_adaptation_task.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void definitionAndDedicatedClusterAreDisabledByDefault() throws Exception {
        var definition = definition();
        assertEquals(false, definition.get("enabled"));
        assertEquals(900, ((Number) definition.get("timeout_seconds")).intValue());
        assertEquals("IMMEDIATE", definition.get("task_type"));
        assertEquals("SINGLE_NODE", definition.get("execution_mode"));
        assertEquals(2, ((Number) definition.get("max_concurrency")).intValue());
        assertEquals("QUEUE", definition.get("overlap_policy"));
        assertNull(definition.get("cron_expression")); assertNull(definition.get("cron_timezone"));
        var cluster = jdbc.queryForMap("SELECT * FROM execution_cluster WHERE id = ?", definition.get("cluster_id"));
        assertEquals("reader-adaptation", cluster.get("name")); assertEquals(false, cluster.get("enabled"));
        assertEquals(Map.of("reader.adaptation", "enabled"), json(cluster.get("labels_json")));
    }

    @Test
    void acceptsOnlyOneCanonicalAdaptationIdentifier() throws Exception {
        Map<String, Object> schema = json(definition().get("parameter_schema"));
        assertDoesNotThrow(() -> schemas.validateParameters(schema, Map.of("adaptationId", UUID.randomUUID().toString())));
        for (Map<String, Object> value : List.of(Map.<String, Object>of(), Map.<String, Object>of("adaptationId", "invalid"),
                Map.<String, Object>of("adaptationId", "ABCDEF00-0000-4000-8000-000000000001"),
                Map.<String, Object>of("adaptationId", UUID.randomUUID().toString(), "intent", "test-only"),
                Map.<String, Object>of("adaptationId", UUID.randomUUID().toString(), "ownerId", 1))) {
            assertThrows(SchedulerException.class, () -> schemas.validateParameters(schema, value));
        }
    }

    @Test
    void resultSchemaCannotCarryTextOrCredentialsBackToScheduler() throws Exception {
        Map<String, Object> schema = json(definition().get("result_schema"));
        assertDoesNotThrow(() -> schemas.validateResult(schema, Map.of()));
        for (String field : List.of("body", "intent", "providerKey", "workloadAssertion", "status")) {
            assertThrows(SchedulerException.class, () -> schemas.validateResult(schema, Map.of(field, "test-only")));
        }
    }

    @Test
    void singleBrokerStepMatchesHostAndPackageContract() throws Exception {
        var rows = jdbc.queryForList("SELECT * FROM task_step_definition WHERE task_definition_id = ?", definition().get("id"));
        assertEquals(1, rows.size()); var step = rows.getFirst();
        assertEquals("run", step.get("name")); assertEquals("NORMAL", step.get("step_kind"));
        assertEquals(TASK, step.get("script_package")); assertEquals("1.0.0", step.get("script_version"));
        assertEquals("scripts/main.py", step.get("entrypoint")); assertEquals(true, step.get("enabled"));
        assertEquals(900, ((Number) step.get("timeout_seconds")).intValue());
        assertEquals(1, ((Number) step.get("max_attempts")).intValue());
        assertEquals(1, ((Number) step.get("sequence_number")).intValue());
        assertEquals("FAIL_TASK", step.get("failure_policy"));
        assertEquals("[]", step.get("arguments_template"));
        Path current = Path.of("").toAbsolutePath();
        Path service = null;
        for (Path directory = current; directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve("reader-service/packages"))) { service = directory; break; }
            if (Files.isDirectory(directory.resolve("service/reader-service/packages"))) { service = directory.resolve("service"); break; }
        }
        assertNotNull(service);
        Path packageRoot = service.resolve("reader-service/packages/" + TASK + "/1.0.0");
        assertTrue(Files.readString(packageRoot.resolve("manifest.yaml")).contains("entrypoint: scripts/main.py"));
        assertTrue(Files.isRegularFile(packageRoot.resolve("scripts/main.py")));
        Map<String, Object> packageSchema = JSON.readValue(Files.readString(packageRoot.resolve("schemas/result.schema.json")), new TypeReference<>() { });
        assertEquals("https://json-schema.org/draft/2020-12/schema", packageSchema.remove("$schema"));
        // 比较本任务使用的共同 schema 关键字，不把草稿版本标识当作业务结果字段。
        assertEquals(json(definition().get("result_schema")), packageSchema);
    }

    private static Map<String, Object> definition() {
        return jdbc.queryForMap("SELECT * FROM task_definition WHERE name = ? AND version = ?", TASK, 1);
    }

    private static Map<String, Object> json(Object value) throws Exception {
        return JSON.readValue(value.toString(), new TypeReference<>() { });
    }
}
