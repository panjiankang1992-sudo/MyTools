package com.yuyutian.mytools.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.news.Model.DeliveryModels.Content;
import com.yuyutian.mytools.news.Model.DeliveryModels.Receipt;
import com.yuyutian.mytools.news.Model.DeliveryModels.Channel;
import com.yuyutian.mytools.news.Model.NewsModels.Edition;
import com.yuyutian.mytools.news.Model.NewsModels.RegionSnapshot;
import com.yuyutian.mytools.news.common.NewsException;
import com.yuyutian.mytools.news.config.DeliveryProperties;
import com.yuyutian.mytools.news.config.RetentionProperties;
import com.yuyutian.mytools.news.mapper.DispatchRepository;
import com.yuyutian.mytools.news.mapper.GithubSnapshotRepository;
import com.yuyutian.mytools.news.mapper.NewsRepository;
import com.yuyutian.mytools.news.mapper.RetentionRepository;
import com.yuyutian.mytools.news.service.DigestEngine;
import com.yuyutian.mytools.news.service.NewsDeliveryRenderer;
import com.yuyutian.mytools.news.utils.ContentIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式接入带随机身份标记的临时 MySQL，不能使用业务库代替隔离验收。 */
@EnabledIfEnvironmentVariable(named = "NEWS_MYSQL_TEST_CONFIG", matches = ".+")
class MySqlCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NOW = Fixtures.CUTOFF;
    private static JsonNode configuration;
    private static Path serviceRoot;

    @BeforeAll
    static void validateIsolatedConfiguration() throws Exception {
        Path path = Path.of(System.getenv("NEWS_MYSQL_TEST_CONFIG"));
        assertThat(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.size(path)).isLessThan(8192);
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try {
            configuration = JSON.readTree(Files.readString(path));
        } catch (IOException exception) {
            // 解析器错误可能携带输入片段，不能将私有测试凭据放入失败报告。
            throw new IllegalStateException("MYSQL_TEST_CONFIG_INVALID");
        }
        assertThat(configuration.path("host").asText()).isEqualTo("127.0.0.1");
        assertThat(configuration.path("port").asInt()).isBetween(1024, 65535);
        assertThat(configuration.path("user").asText()).isEqualTo("news_verify");
        // 只断言形状，不将任何秘密作为失败断言的实际值输出。
        assertThat(configuration.path("password").asText().length() >= 32).isTrue();
        assertThat(configuration.path("marker").asText().matches("[a-f0-9]{32}")).isTrue();
        String selectedRoot = System.getProperty("news.mysql.serviceRoot");
        if (selectedRoot == null) {
            selectedRoot = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().getParent().toString();
        }
        serviceRoot = Path.of(selectedRoot).toRealPath();
        assertThat(Files.isDirectory(serviceRoot.resolve("news-digest-service/db/migrations"))).isTrue();
        verifyServerIdentity();
    }

    @Test
    void allThreeServicesMigrateFromEmptyAndValidateIdempotently() throws Exception {
        // 完整历史迁移在同一个真实数据库产品上逐服务执行，不能跳过旧的 JSON 迁移。
        for (String service : List.of("news-digest-service", "messaging-service", "task-scheduler-service")) {
            try (Sandbox database = new Sandbox()) {
                Flyway flyway = migration(database, service, null);
                long count;
                try (var files = Files.list(migrations(service))) {
                    count = files.filter(file -> file.getFileName().toString().matches("V[0-9]+__.+\\.sql")).count();
                }
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo((int) count);
                flyway.validate();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE", Integer.class)).isZero();
            }
        }
    }

    @Test
    void deployedSchedulerWithLaterMigrationCanAddMissingNewsSeedsWithoutRepair() throws Exception {
        Path deployed = Files.createTempDirectory("news-scheduler-deployed-");
        try (Sandbox database = new Sandbox()) {
            // 复现现场 V1—V172、V175 已应用，新闻 V173/V174 尚未发布的真实顺序。
            try (var files = Files.list(migrations("task-scheduler-service"))) {
                for (Path file : files.toList()) {
                    var match = java.util.regex.Pattern.compile("V([0-9]+)__.+\\.sql").matcher(file.getFileName().toString());
                    if (match.matches() && (Integer.parseInt(match.group(1)) <= 172 || match.group(1).equals("175")))
                        Files.copy(file, deployed.resolve(file.getFileName()));
                }
            }
            Flyway.configure().dataSource(database.data).locations("filesystem:" + deployed).load().migrate();
            var original = database.jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            assertThat(original).hasSize(173);
            Flyway current = migration(database, "task-scheduler-service", null);
            assertThatThrownBy(current::validate).isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
            // 只有明确识别到的四个新闻相关迁移可用于本次补齐，不放开任意待执行迁移。
            Flyway upgrade = Flyway.configure().dataSource(database.data)
                    .locations("filesystem:" + migrations("task-scheduler-service")).outOfOrder(true).load();
            assertThat(Arrays.stream(upgrade.info().pending()).map(info -> info.getVersion().getVersion()))
                    .containsExactly("173", "174", "176", "177");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
            assertThat(database.jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE installed_rank<=173 ORDER BY installed_rank"))
                    .isEqualTo(original);
            // 补齐后关闭乱序选项仍可正常校验、重放，不修改已应用历史或任务开关。
            current.validate();
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM task_definition WHERE name IN ('news_generate_edition','news_resolve_region','message_send_channel_message') AND enabled=FALSE", Integer.class)).isEqualTo(3);
            assertThat(database.jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package='news_resolve_region'", String.class)).isEqualTo("1.2.0");
        } finally {
            try (var files = Files.list(deployed)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(deployed);
        }
    }

    @Test
    void previousFlywayHistoryUpgradesWithoutRepairOrChecksumChanges() throws Exception {
        assertThat(org.flywaydb.core.internal.license.VersionPrinter.getVersion()).isEqualTo("11.20.3");
        for (String service : List.of("news-digest-service", "messaging-service", "task-scheduler-service")) {
            try (Sandbox database = new Sandbox()) {
                String target = switch (service) {
                    case "news-digest-service" -> "6";
                    case "messaging-service" -> "19";
                    default -> "172";
                };
                legacyBaseline(database.schema, service, target);
                var original = database.jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
                assertThat(original).hasSize(Integer.parseInt(target));
                migration(database, service, target).validate();
                Flyway current = migration(database, service, null);
                assertThat(current.migrate().migrationsExecuted).isGreaterThan(0);
                current.validate();
                // 比较完整旧历史行，不能使用 repair、baseline 或重新计算校验和掩盖引擎升级问题。
                assertThat(database.jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE installed_rank<=? ORDER BY installed_rank",
                        original.size())).isEqualTo(original);
                assertThat(current.migrate().migrationsExecuted).isZero();
            }
        }
    }

    @Test
    void engineUpgradeRejectsChangedOldChecksumBeforeApplyingNewMigration() throws Exception {
        try (Sandbox database = new Sandbox()) {
            legacyBaseline(database.schema, "news-digest-service", "6");
            Integer original = database.jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class);
            database.jdbc.update("UPDATE flyway_schema_history SET checksum=? WHERE version='1'", original ^ 1);
            // 损坏历史校验和时，新引擎必须先失败，不能新增迁移或自动修复历史。
            assertThatThrownBy(() -> migration(database, "news-digest-service", null).migrate())
                    .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(6);
            assertThat(database.jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'", Integer.class)).isEqualTo(original ^ 1);
        }
    }

    @Test
    void populatedCollectionCapacityMigrationPreservesUtf8AndBlocksUnmeteredWrites() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database,"news-digest-service","7").migrate();
            CollectionCapacityTest.seedLegacy(database.jdbc);
            String before=database.jdbc.queryForObject("SELECT payload_json FROM news_article",String.class);
            assertThat(migration(database,"news-digest-service","8").migrate().migrationsExecuted).isEqualTo(1);
            CollectionCapacityTest.assertMigratedUsage(database.jdbc,before);
        }
    }

    @Test
    void collectionCapacitySerializesConcurrentInstancesOnMySql() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();
            CollectionCapacityTest.concurrentAdmission(database.jdbc);
        }
    }

    @Test
    void collectionCleanupAndAdmissionUseConsistentLocksOnMySql() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();
            CollectionCapacityTest.concurrentCleanup(database.jdbc);
        }
    }

    @Test
    void controlIdentityMigrationPreservesLegacyAndAllowsAcceptedRunCompletion() throws Exception {
        try (Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service","8").migrate();
            ControlCapacityTest.seedLegacy(database.jdbc);
            String before=database.jdbc.queryForObject("SELECT payload_json FROM news_edition",String.class);
            assertThat(migration(database,"news-digest-service","9").migrate().migrationsExecuted).isEqualTo(1);
            ControlCapacityTest.assertLegacy(database.jdbc,before);
        }
    }

    @Test
    void acceptedRunCanPublishAtFullControlCapacityOnMySql() throws Exception {
        try (Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();
            ControlCapacityTest.acceptedRunFinishes(database.jdbc);
        }
    }

    @Test
    void concurrentPreviewAdmissionIsBoundedOnMySql() throws Exception {
        try (Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();
            ControlCapacityTest.concurrentPreview(database.jdbc);
        }
    }

    @Test
    void concurrentScheduledRegionBindingReplaysOnMySql() throws Exception {
        try (Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();
            ControlCapacityTest.concurrentScheduledBinding(database.jdbc);
        }
    }

    private static void legacyBaseline(String schema, String service, String target) throws Exception {
        String selected = System.getProperty("news.mysql.legacyLibraryDirectory");
        Path directory = selected == null ? Path.of(System.getProperty("user.home"), ".m2", "repository") : Path.of(selected);
        List<Path> legacy = selected == null ? List.of(
                directory.resolve("org/flywaydb/flyway-core/9.22.3/flyway-core-9.22.3.jar"),
                directory.resolve("org/flywaydb/flyway-mysql/9.22.3/flyway-mysql-9.22.3.jar"),
                directory.resolve("com/google/code/gson/gson/2.10.1/gson-2.10.1.jar")) : List.of(
                directory.resolve("flyway-core-9.22.3.jar"), directory.resolve("flyway-mysql-9.22.3.jar"),
                directory.resolve("gson-2.10.1.jar"));
        assertThat(legacy.stream().allMatch(Files::isRegularFile)).isTrue();
        // Surefire 启动器可能只把自身 JAR 放进 java.class.path；使用实际测试依赖避免夹带新引擎。
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> entries = new ArrayList<>(legacy.stream().map(Path::toString).toList());
        entries.addAll(Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("flyway-"))
                .toList());
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dnews.mysql.serviceRoot=" + serviceRoot, "-cp", String.join(File.pathSeparator, entries),
                LegacyFlywayBaselineLauncher.class.getName(), service, schema, target);
        builder.environment().clear();
        builder.environment().put("NEWS_MYSQL_TEST_CONFIG", System.getenv("NEWS_MYSQL_TEST_CONFIG"));
        builder.environment().put("LANG", "C.UTF-8");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            // 超时或断言失败时也必须停止子进程，不能让它与 schema 清理并行运行。
            if (process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    static void createLegacyBaseline(String service, String schema, String target) throws Exception {
        validateIsolatedConfiguration();
        assertThat(org.flywaydb.core.internal.license.VersionPrinter.getVersion()).isEqualTo("9.22.3");
        assertThat(List.of("news-digest-service", "messaging-service", "task-scheduler-service")).contains(service);
        assertThat(schema.matches("news_it_[a-f0-9]{32}")).isTrue();
        assertThat(target.matches("[0-9]{1,3}")).isTrue();
        verifyServerIdentity();
        Flyway.configure().dataSource(dataSource(schema)).locations("filesystem:" + migrations(service)).target(target).load().migrate();
    }

    @Test
    void newsUpgradeKeepsEditionBodyOwnerAndOriginalOutboxIdentity() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database, "news-digest-service", "1").migrate();
            Edition edition = new DigestEngine().build(UUID.randomUUID(), 77, NOW, NOW, ZoneOffset.UTC,
                    RegionSnapshot.unresolved(), List.of());
            String body = JSON.writeValueAsString(edition);
            database.jdbc.update("INSERT INTO news_edition(id,owner_id,idempotency_hash,request_hash,cutoff_ms,created_ms,quality,payload_json) VALUES(?,?,?,?,?,?,?,?)",
                    edition.id().toString(), 77, ContentIdentity.hash("original-key"), "original-request",
                    NOW.toEpochMilli(), NOW.toEpochMilli(), "DEGRADED", body);
            migration(database, "news-digest-service", "4").migrate();
            UUID run = UUID.randomUUID();
            database.jdbc.update("INSERT INTO news_task_outbox(run_id,state,available_ms) VALUES(?,'PENDING',0)", run.toString());
            migration(database, "news-digest-service", null).migrate();
            assertThat(database.jdbc.queryForObject("SELECT payload_json FROM news_edition", String.class)).isEqualTo(body);
            assertThat(database.jdbc.queryForObject("SELECT job_kind FROM news_task_outbox WHERE run_id=?", String.class, run.toString())).isEqualTo("EDITION");
            NewsRepository repository = news(database);
            assertThat(repository.findByKey(77, "original-key", "original-request")).contains(edition);
            assertThatThrownBy(() -> repository.edition(78, edition.id())).isInstanceOf(NewsException.class);
        }
    }

    @Test
    void messagingUpgradePreservesOldEmailAndFrozenV19Content() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database, "messaging-service", "17").migrate();
            UUID legacy = UUID.randomUUID();
            database.jdbc.update("INSERT INTO delivery_request(id,owner_id,idempotency_key,channel_type,recipient,body_text,status,created_at,updated_at) VALUES(?,77,'legacy-key','EMAIL','fixture@example.test',?,'SENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    legacy.toString(), "Legacy body \uD83D\uDE80");
            migration(database, "messaging-service", "19").migrate();
            UUID destination = UUID.randomUUID(), outbound = UUID.randomUUID();
            String body = JSON.writeValueAsString(new Content("Fixture", "Frozen \uD83D\uDE80", null));
            database.jdbc.update("INSERT INTO messaging_destination(id,owner_id,binding_key,channel,label,secret_cipher,verification_reference,enabled,created_ms,updated_ms) VALUES(?,77,'binding','EMAIL','Fixture','fixture-cipher','fixture-only',TRUE,0,0)", destination.toString());
            database.jdbc.update("INSERT INTO messaging_outbound(id,owner_id,idempotency_key,destination_id,channel,content_json,payload_hash,status,expires_ms,next_attempt_ms,created_ms,updated_ms) VALUES(?,77,'frozen-key',?,'EMAIL',?,?,'FAILED',0,0,0,0)",
                    outbound.toString(), destination.toString(), body, "a".repeat(64));
            migration(database, "messaging-service", null).migrate();
            assertThat(database.jdbc.queryForObject("SELECT body_text FROM delivery_request WHERE id=?", String.class, legacy.toString())).isEqualTo("Legacy body \uD83D\uDE80");
            assertThat(database.jdbc.queryForObject("SELECT status FROM delivery_request WHERE id=?", String.class, legacy.toString())).isEqualTo("SENT");
            assertThat(database.jdbc.queryForObject("SELECT content_json FROM messaging_outbound WHERE id=?", String.class, outbound.toString())).isEqualTo(body);
            // V20 允许释放正文，但保留原 owner、原键和摘要，不能重新插入相同逻辑投递。
            database.jdbc.update("UPDATE messaging_outbound SET content_json=NULL,capacity_bytes=0,content_identity_hash=?,content_expired_ms=1 WHERE id=?", "b".repeat(64), outbound.toString());
            assertThat(database.jdbc.queryForObject("SELECT payload_hash FROM messaging_outbound WHERE owner_id=77 AND idempotency_key='frozen-key'", String.class)).isEqualTo("a".repeat(64));
            assertThat(database.jdbc.queryForObject("SELECT last_status FROM messaging_outbound_retention_state WHERE id=1", String.class)).isEqualTo("NEVER_RUN");
        }
    }

    @Test
    void registryMigrationPreservesReportsAndPendingJobReservationOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service","9").migrate();RegistryCapacityTest.seedLegacy(database.jdbc);
            var before=database.jdbc.queryForList("SELECT id,payload_json,received_ms FROM news_region_observation ORDER BY id");
            assertThat(migration(database,"news-digest-service",null).migrate().migrationsExecuted).isEqualTo(1);
            assertThat(database.jdbc.queryForList("SELECT id,payload_json,received_ms FROM news_region_observation ORDER BY id")).isEqualTo(before);
            RegistryCapacityTest.assertLegacy(database.jdbc);
        }
    }

    @Test
    void acceptedRegionJobFinishesAtFullRegistryCapacityOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();RegistryCapacityTest.acceptedJobFinishes(database.jdbc);
        }
    }

    @Test
    void concurrentSourceAndDirectObservationAdmissionIsBoundedOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();RegistryCapacityTest.concurrentRegistrations(database.jdbc);
        }
    }

    @Test
    void concurrentRegionJobsReserveOneNodeAndReportSlotOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"news-digest-service",null).migrate();RegistryCapacityTest.concurrentJobs(database.jdbc);
        }
    }

    @Test
    void messagingCapacityBackfillsAndCleansUpOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service","20").migrate();
            messagingCapacityScenario("seedLegacy",database.jdbc);
            assertThat(migration(database,"messaging-service",null).migrate().migrationsExecuted).isEqualTo(2);
            messagingCapacityScenario("verifyMigrationAndCleanup",database.jdbc);
        }
    }

    @Test
    void messagingCapacitySerializesConcurrentAdmissionOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service",null).migrate();
            messagingCapacityScenario("concurrentAdmission",database.jdbc);
        }
    }

    @Test
    void messagingCancellationAndCreateRaceOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service",null).migrate();
            messagingCapacityScenario("cancellationRace",database.jdbc);
        }
    }

    @Test
    void messagingDeadlineUpgradePreservesLegacyPolicyOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service","21").migrate();messagingCapacityScenario("seedDeadlineLegacy",database.jdbc);
            assertThat(migration(database,"messaging-service",null).migrate().migrationsExecuted).isEqualTo(1);
            messagingCapacityScenario("verifyDeadlineUpgrade",database.jdbc);
        }
    }
    @Test
    void messagingDeadlineCleanupPreservesReconciliationOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service",null).migrate();messagingCapacityScenario("deadlineCleanupKeepsReconciliation",database.jdbc);
        }
    }
    @Test
    void messagingDeadlineRollbackAndLeaseOnMySql() throws Exception {
        try(Sandbox database=new Sandbox()) {
            migration(database,"messaging-service",null).migrate();messagingCapacityScenario("deadlineRollbackAndLease",database.jdbc);
        }
    }
    private void messagingCapacityScenario(String method,JdbcTemplate jdbc) throws Exception {
        // 隔离验收包加载 Messaging 自身测试场景，News 生产模块不依赖消息服务实现。
        Class<?> scenarios=Class.forName("com.yuyutian.mytools.messaging.service.OutboundCapacityDatabaseScenarios");
        try {scenarios.getMethod(method,JdbcTemplate.class).invoke(null,jdbc);}
        catch(java.lang.reflect.InvocationTargetException exception) {
            Throwable cause=exception.getCause();
            if(cause instanceof Exception checked)throw checked;
            if(cause instanceof Error error)throw error;
            throw exception;
        }
    }

    @Test
    void schedulerUpgradeAddsDisabledPinnedTasksWithoutChangingOldEmailTask() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database, "task-scheduler-service", "172").migrate();
            database.jdbc.update("UPDATE task_definition SET enabled=TRUE,timeout_seconds=333 WHERE name='message_send_email'");
            assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM task_definition WHERE name='message_send_email'", Integer.class)).isEqualTo(1);
            migration(database, "task-scheduler-service", null).migrate();
            assertThat(database.jdbc.queryForObject("SELECT timeout_seconds FROM task_definition WHERE name='message_send_email'", Integer.class)).isEqualTo(333);
            assertThat(database.jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name='message_send_email'", Boolean.class)).isTrue();
            for (String name : List.of("news_generate_edition", "news_resolve_region", "message_send_channel_message")) {
                assertThat(database.jdbc.queryForObject("SELECT enabled FROM task_definition WHERE name=?", Boolean.class, name)).isFalse();
                assertThat(database.jdbc.queryForObject("SELECT script_version FROM task_step_definition WHERE script_package=?", String.class, name))
                        .isEqualTo(name.equals("news_resolve_region") ? "1.1.0" : "1.0.0");
                assertThat(database.jdbc.queryForObject("SELECT COUNT(*) FROM task_instance WHERE task_name=?", Integer.class, name)).isZero();
            }
            assertThat(database.jdbc.queryForObject("SELECT enabled FROM execution_cluster WHERE name='news'", Boolean.class)).isFalse();
        }
    }

    @Test
    void realDispatchRepositoryHasOneConcurrentLeaseAndRejectsStaleReceipt() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database, "news-digest-service", null).migrate();
            UUID part = part(database, "PENDING", null, NOW.plusSeconds(3600), NOW);
            DeliveryProperties properties = new DeliveryProperties(false, "http://localhost", "", "");
            DispatchRepository repository = new DispatchRepository(database.jdbc, database.manager, JSON, properties, new NewsDeliveryRenderer(JSON, properties), Fixtures.control(database.jdbc));
            CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
            List<com.yuyutian.mytools.news.Model.DeliveryModels.Claim> claims = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(8)) {
                List<Future<java.util.Optional<com.yuyutian.mytools.news.Model.DeliveryModels.Claim>>> futures = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    futures.add(pool.submit(() -> { ready.countDown();assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();return repository.claim(NOW); }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();start.countDown();
                for (var future : futures) future.get(30, TimeUnit.SECONDS).ifPresent(claims::add);
            }
            assertThat(claims).hasSize(1);assertThat(claims.getFirst().id()).isEqualTo(part);
            assertThat(repository.claim(NOW.plusSeconds(59))).isEmpty();
            var recovered = repository.claim(NOW.plusSeconds(61)).orElseThrow();
            assertThat(recovered.token()).isNotEqualTo(claims.getFirst().token());
            var batch = repository.batch(repository.part(part, false).batchId(), false);
            Receipt receipt = new Receipt(UUID.randomUUID(), batch.destinationId(), Channel.EMAIL, "a".repeat(64), "PROVIDER_ACCEPTED", 1, null, batch.expiresAt(), NOW.plusSeconds(61));
            assertThatThrownBy(() -> repository.reconcile(claims.getFirst(), receipt, NOW.plusSeconds(61))).isInstanceOf(NewsException.class);
            repository.reconcile(recovered, receipt, NOW.plusSeconds(61));
            assertThat(repository.part(part, false).status()).isEqualTo("PROVIDER_ACCEPTED");
            assertThat(repository.claim(NOW.plusSeconds(130))).isEmpty();
        }
    }

    @Test
    void realRetentionClearsEligibleBodiesButKeepsUnknownAndOwnerIdentity() throws Exception {
        try (Sandbox database = new Sandbox()) {
            migration(database, "news-digest-service", null).migrate();
            NewsRepository repository = news(database);
            Instant old = NOW.minus(Duration.ofDays(181));
            Edition retained = new DigestEngine().build(UUID.randomUUID(), 77, old, old, ZoneOffset.UTC, RegionSnapshot.unresolved(), List.of());
            Edition expired = new DigestEngine().build(UUID.randomUUID(), 77, old, old, ZoneOffset.UTC, RegionSnapshot.unresolved(), List.of());
            repository.saveEdition(retained, "retained", "request");repository.saveEdition(expired, "expired", "request");
            UUID unknown = part(database, "UNKNOWN", retained.id(), old, old);
            UUID finished = part(database, "FAILED", null, old, old);
            RetentionRepository retention = new RetentionRepository(database.jdbc, JSON, new TransactionTemplate(database.manager), new RetentionProperties(true, 168, 90, 180, 90, 250), Fixtures.capacity(database.jdbc));
            var counts = retention.sweep(NOW);
            assertThat(counts.get("editions")).isEqualTo(1);assertThat(counts.get("deliveryBodies")).isEqualTo(1);
            assertThat(repository.edition(77, retained.id())).isEqualTo(retained);
            assertThatThrownBy(() -> repository.edition(77, expired.id())).isInstanceOf(NewsException.class);
            assertThatThrownBy(() -> repository.edition(78, retained.id())).isInstanceOf(NewsException.class);
            assertThat(database.jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox WHERE id=?", String.class, unknown.toString())).isNotNull();
            assertThat(database.jdbc.queryForObject("SELECT content_json FROM news_dispatch_outbox WHERE id=?", String.class, finished.toString())).isNull();
            assertThat(database.jdbc.queryForObject("SELECT payload_hash FROM news_dispatch_outbox WHERE id=?", String.class, finished.toString())).isEqualTo("a".repeat(64));
            assertThat(retention.checkpoint().get("last_status")).isEqualTo("SUCCEEDED");
        }
    }

    private static UUID part(Sandbox database, String state, UUID edition, Instant expires, Instant updated) throws Exception {
        UUID batch = UUID.randomUUID(), part = UUID.randomUUID();
        database.jdbc.update("INSERT INTO news_delivery_batch(id,run_id,subscription_id,owner_id,target_revision,destination_id,channel,render_mode,edition_id,state,expires_ms,created_ms,updated_ms) VALUES(?,?,?,77,1,?,'EMAIL','FULL',?,'QUEUED',?,?,?)",
                batch.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                edition == null ? null : edition.toString(), expires.toEpochMilli(), updated.toEpochMilli(), updated.toEpochMilli());
        database.jdbc.update("INSERT INTO news_dispatch_outbox(id,batch_id,part_index,part_count,content_json,payload_hash,status,available_ms,updated_ms) VALUES(?,?,1,1,?,?,?,0,?)",
                part.toString(), batch.toString(), JSON.writeValueAsString(new Content("Fixture", "Frozen \uD83D\uDE80", null)), "a".repeat(64), state, updated.toEpochMilli());
        return part;
    }

    private static NewsRepository news(Sandbox database) {
        return new NewsRepository(database.jdbc, JSON, new TransactionTemplate(database.manager), new GithubSnapshotRepository(database.jdbc, JSON, Fixtures.capacity(database.jdbc), Fixtures.transaction(database.jdbc)), Fixtures.capacity(database.jdbc), Fixtures.control(database.jdbc), Fixtures.registry(database.jdbc));
    }

    private static Path migrations(String service) {
        Path project = serviceRoot.resolve(service);
        return project.resolve(service.equals("task-scheduler-service") ? "src/main/resources/db/migration" : "db/migrations");
    }

    private static Flyway migration(Sandbox database, String service, String target) {
        FluentConfiguration builder = Flyway.configure().dataSource(database.data).locations("filesystem:" + migrations(service));
        if (target != null) builder.target(target);
        return builder.load();
    }

    private static DriverManagerDataSource dataSource(String schema) {
        String url = "jdbc:mysql://127.0.0.1:" + configuration.path("port").asInt() + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=15000";
        return new DriverManagerDataSource(url, configuration.path("user").asText(), configuration.path("password").asText());
    }

    private static void verifyServerIdentity() {
        String actual = new JdbcTemplate(dataSource("news_verification_control"))
                .queryForObject("SELECT marker FROM verification_instance WHERE id=1", String.class);
        assertThat(configuration.path("marker").asText().equals(actual)).isTrue();
    }

    /** 每个用例独占随机 schema，并在退出前再次验证临时实例身份。 */
    private static final class Sandbox implements AutoCloseable {
        private final String schema = "news_it_" + UUID.randomUUID().toString().replace("-", "");
        private final DriverManagerDataSource data;
        private final JdbcTemplate jdbc;
        private final DataSourceTransactionManager manager;

        private Sandbox() {
            verifyServerIdentity();
            // schema 只含固定前缀和随机十六进制，不能来自外部数据库名称。
            new JdbcTemplate(dataSource("news_verification_control")).execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            data = dataSource(schema);jdbc = new JdbcTemplate(data);manager = new DataSourceTransactionManager(data);
        }

        /** 只清理本用例创建的 schema，拒绝身份变化后的实例。 */
        @Override
        public void close() {
            verifyServerIdentity();
            new JdbcTemplate(dataSource("news_verification_control")).execute("DROP DATABASE " + schema);
        }
    }
}
