package com.yuyutian.mytools.task.scheduler.service;

import com.yuyutian.mytools.task.scheduler.common.ErrorCode;
import com.yuyutian.mytools.task.scheduler.common.SchedulerException;
import com.yuyutian.mytools.task.scheduler.model.CreateTaskRequest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OneBotReloginTaskDefinitionTest {

    private static final String SEEDED_DEFINITION_ID = "00000000-0000-4000-8000-000000000564";
    private static final String SEEDED_STEP_ID = "00000000-0000-4000-8000-000000000565";

    @TempDir
    Path temporaryDirectory;

    @Autowired
    private TaskDefinitionService taskDefinitionService;

    @Autowired
    private TaskInstanceService taskInstanceService;

    @Autowired
    private TaskSchemaValidationService schemaValidationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateOneBotReloginTaskAndRejectInvalidParameters() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        var created = taskInstanceService.create(new CreateTaskRequest(
                "onebot_relogin", "onebot_relogin_" + suffix, "ONEBOT_RELOGIN", suffix,
                null, 90, Map.of("accountKey", "qq-primary", "requestId", "qq_123")));

        assertEquals("onebot_relogin", created.taskName());
        assertEquals("QUEUED", created.status().name());
        SchedulerException invalid = assertThrows(SchedulerException.class,
                () -> taskInstanceService.create(new CreateTaskRequest(
                        "onebot_relogin", "onebot_relogin_invalid_" + suffix, "ONEBOT_RELOGIN", suffix,
                        null, 90, Map.of("accountKey", "../unsafe", "requestId", "qq_123"))));
        assertEquals(ErrorCode.TASK_PARAMETER_SCHEMA_INVALID, invalid.errorCode());
    }

    @Test
    void shouldMatchOneBotReloginPackageAndResultContract() {
        var definition = taskDefinitionService.list().stream()
                .filter(candidate -> candidate.name().equals("onebot_relogin"))
                .findFirst().orElseThrow();
        Map<String, Object> qrReadyResult = Map.of(
                "accountKey", "qq-primary",
                "requestId", "qq_123",
                "requestedAt", "2026-09-08T10:00:00+00:00",
                "status", "QR_READY");
        Map<String, Object> alreadyOnlineResult = Map.of(
                "accountKey", "qq-primary",
                "requestId", "qq_123",
                "status", "ALREADY_ONLINE");

        assertTrue(definition.enabled());
        assertEquals(180, definition.timeoutSeconds());
        assertDoesNotThrow(() -> schemaValidationService.validateResult(definition.resultSchema(), qrReadyResult));
        assertDoesNotThrow(() -> schemaValidationService.validateResult(definition.resultSchema(), alreadyOnlineResult));
        SchedulerException requested = assertThrows(SchedulerException.class,
                () -> schemaValidationService.validateResult(definition.resultSchema(), Map.of(
                        "accountKey", "qq-primary", "requestId", "qq_123",
                        "requestedAt", "2026-09-08T10:00:00+00:00", "status", "REQUESTED")));
        SchedulerException failed = assertThrows(SchedulerException.class,
                () -> schemaValidationService.validateResult(definition.resultSchema(), Map.of(
                        "accountKey", "qq-primary", "requestId", "qq_123", "status", "FAILED")));
        SchedulerException onlineWithTimestamp = assertThrows(SchedulerException.class,
                () -> schemaValidationService.validateResult(definition.resultSchema(), Map.of(
                        "accountKey", "qq-primary", "requestId", "qq_123",
                        "requestedAt", "2026-09-08T10:00:00+00:00", "status", "ALREADY_ONLINE")));
        assertEquals(ErrorCode.TASK_RESULT_SCHEMA_INVALID, requested.errorCode());
        assertEquals(ErrorCode.TASK_RESULT_SCHEMA_INVALID, failed.errorCode());
        assertEquals(ErrorCode.TASK_RESULT_SCHEMA_INVALID, onlineWithTimestamp.errorCode());

        Map<String, Object> step = jdbcTemplate.queryForMap("""
                SELECT script_package,script_version,entrypoint,timeout_seconds,max_attempts
                FROM task_step_definition
                WHERE task_definition_id = ? AND name = 'request_relogin'
                """, definition.id().toString());
        assertEquals("onebot_relogin", step.get("script_package"));
        assertEquals("1.0.0", step.get("script_version"));
        assertEquals("scripts/main.py", step.get("entrypoint"));
        assertEquals(180, ((Number) step.get("timeout_seconds")).intValue());
        assertEquals(1, ((Number) step.get("max_attempts")).intValue());

        Path packageRoot = resolveServiceRoot().resolve(
                "onebot-connector-service/packages/onebot_relogin/1.0.0");
        assertTrue(Files.isRegularFile(packageRoot.resolve("manifest.yaml")));
        assertTrue(Files.isRegularFile(packageRoot.resolve("scripts/main.py")));
    }

    @Test
    void shouldSeedOneBotReloginDefinitionIntoDatabaseWithoutExistingDefinition() {
        MigrationFixture fixture = migrateToVersion137("empty");

        fixture.migrateToVersion138();

        assertMigratedContract(fixture.jdbcTemplate(), SEEDED_DEFINITION_ID, SEEDED_STEP_ID);
    }

    @Test
    void shouldNormalizeExistingDefinitionAndStepWithoutReplacingTheirIdentifiers() {
        MigrationFixture fixture = migrateToVersion137("existing");
        String definitionId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String taskInstanceId = UUID.randomUUID().toString();
        fixture.jdbcTemplate().update("""
                INSERT INTO task_definition (
                    id,name,description,task_type,timeout_seconds,cluster_id,cron_expression,cron_timezone,
                    execution_mode,enabled,max_concurrency,overlap_policy,misfire_policy,parameter_schema,
                    result_schema,child_aggregation_policy_json,version,created_at,updated_at
                ) VALUES (?,?,?,'IMMEDIATE',99,'00000000-0000-4000-8000-000000000004',NULL,NULL,
                    'SINGLE_NODE',FALSE,2,'SKIP','IGNORE','{}','{}',?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, definitionId, "onebot_relogin", "Legacy relogin definition",
                "{\"strategy\":\"ALL_SUCCESS\",\"minSuccessCount\":null}");
        fixture.jdbcTemplate().update("""
                INSERT INTO task_step_definition (
                    id,task_definition_id,name,description,step_kind,script_package,script_version,entrypoint,
                    arguments_template,enabled,timeout_seconds,failure_policy,sequence_number,max_attempts,
                    created_at,updated_at
                ) VALUES (?,?,'request_login_qr','Legacy QR request','NORMAL','legacy_relogin','0.1.0',
                    'legacy.py','[]',FALSE,30,'FAIL_TASK',0,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, stepId, definitionId);
        fixture.jdbcTemplate().update("""
                INSERT INTO task_instance (
                    id,task_definition_id,task_definition_version,task_name,idempotency_key,
                    parent_task_instance_id,business_type,business_id,priority,parameters_json,status,progress,
                    required_node_labels_json,created_at,updated_at
                ) VALUES (?,?,1,'onebot_relogin',?,NULL,'ONEBOT_RELOGIN','legacy',50,'{}','SUCCEEDED',100,
                    '{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, taskInstanceId, definitionId, "legacy_relogin_" + UUID.randomUUID());

        fixture.migrateToVersion138();

        assertMigratedContract(fixture.jdbcTemplate(), definitionId, stepId);
        assertEquals(1, fixture.jdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM task_instance WHERE id = ? AND task_definition_id = ?
                """, Integer.class, taskInstanceId, definitionId));
        assertEquals(0, fixture.jdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM task_step_definition
                WHERE task_definition_id = ? AND name = 'request_login_qr'
                """, Integer.class, definitionId));
    }

    private MigrationFixture migrateToVersion137(String databaseName) {
        String databaseUrl = "jdbc:h2:file:"
                + temporaryDirectory.resolve(databaseName).toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("137"))
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(databaseUrl, "sa", "");
        return new MigrationFixture(databaseUrl, new JdbcTemplate(dataSource));
    }

    private void assertMigratedContract(JdbcTemplate migrationJdbcTemplate,
                                        String expectedDefinitionId,
                                        String expectedStepId) {
        Map<String, Object> definition = migrationJdbcTemplate.queryForMap("""
                SELECT id,task_type,timeout_seconds,cluster_id,execution_mode,enabled,max_concurrency,
                       overlap_policy,misfire_policy,parameter_schema,result_schema,
                       child_aggregation_policy_json
                FROM task_definition
                WHERE name = 'onebot_relogin' AND version = 1
                """);
        assertEquals(expectedDefinitionId, definition.get("id"));
        assertEquals("IMMEDIATE", definition.get("task_type"));
        assertEquals(180, ((Number) definition.get("timeout_seconds")).intValue());
        assertEquals("00000000-0000-4000-8000-000000000004", definition.get("cluster_id"));
        assertEquals("SINGLE_NODE", definition.get("execution_mode"));
        assertEquals(Boolean.TRUE, definition.get("enabled"));
        assertEquals(1, ((Number) definition.get("max_concurrency")).intValue());
        assertEquals("QUEUE", definition.get("overlap_policy"));
        assertEquals("IGNORE", definition.get("misfire_policy"));
        assertTrue(definition.get("parameter_schema").toString().contains("accountKey"));
        assertTrue(definition.get("result_schema").toString().contains("QR_READY"));
        assertTrue(definition.get("result_schema").toString().contains("ALREADY_ONLINE"));
        assertEquals("{\"strategy\":\"ALL_SUCCESS\",\"minSuccessCount\":null}",
                definition.get("child_aggregation_policy_json"));

        Map<String, Object> step = migrationJdbcTemplate.queryForMap("""
                SELECT id,name,step_kind,script_package,script_version,entrypoint,arguments_template,
                       enabled,timeout_seconds,failure_policy,sequence_number,max_attempts
                FROM task_step_definition
                WHERE task_definition_id = ? AND name = 'request_relogin'
                """, expectedDefinitionId);
        assertEquals(expectedStepId, step.get("id"));
        assertEquals("request_relogin", step.get("name"));
        assertEquals("NORMAL", step.get("step_kind"));
        assertEquals("onebot_relogin", step.get("script_package"));
        assertEquals("1.0.0", step.get("script_version"));
        assertEquals("scripts/main.py", step.get("entrypoint"));
        assertEquals("[]", step.get("arguments_template"));
        assertEquals(Boolean.TRUE, step.get("enabled"));
        assertEquals(180, ((Number) step.get("timeout_seconds")).intValue());
        assertEquals("FAIL_TASK", step.get("failure_policy"));
        assertEquals(10, ((Number) step.get("sequence_number")).intValue());
        assertEquals(1, ((Number) step.get("max_attempts")).intValue());
    }

    private Path resolveServiceRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        return "task-scheduler-service".equals(workingDirectory.getFileName().toString())
                ? workingDirectory.getParent() : workingDirectory.resolve("service");
    }

    private record MigrationFixture(String databaseUrl, JdbcTemplate jdbcTemplate) {

        private void migrateToVersion138() {
            Flyway.configure()
                    .dataSource(databaseUrl, "sa", "")
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("138"))
                    .load()
                    .migrate();
        }
    }
}
