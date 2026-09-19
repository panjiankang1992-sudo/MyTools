package com.yuyutian.mytools.task.scheduler.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只验证部署配置的窄事务，不把 H2 结果作为生产 MySQL 迁移证据。 */
class AdaptationDeploymentInitializerTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MockEnvironment environment;

    @BeforeEach
    void prepare() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE execution_cluster(id VARCHAR PRIMARY KEY,name VARCHAR,enabled BOOLEAN,description VARCHAR,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_definition(id VARCHAR PRIMARY KEY,name VARCHAR,cluster_id VARCHAR,enabled BOOLEAN,description VARCHAR,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_step_definition(task_definition_id VARCHAR,script_package VARCHAR,script_version VARCHAR,entrypoint VARCHAR,enabled BOOLEAN)");
        jdbc.update("INSERT INTO execution_cluster(id,name,enabled) VALUES (?,?,FALSE)", "c92ac466-14e2-48b7-b551-bd7a676aa0a7", "reader-adaptation");
        jdbc.update("INSERT INTO task_definition(id,name,cluster_id,enabled) VALUES (?,?,?,FALSE)", "d9c1c5ef-4656-4770-9976-955e93906e93", "reader_adapt_novel_chapter", "c92ac466-14e2-48b7-b551-bd7a676aa0a7");
        jdbc.update("INSERT INTO task_step_definition VALUES (?,?,?,?,TRUE)", "d9c1c5ef-4656-4770-9976-955e93906e93", "reader_adapt_novel_chapter", "1.0.0", "scripts/main.py");
        environment = new MockEnvironment().withProperty("task.reader-adaptation-deployment.audit-id", "fixture-v1")
                .withProperty("task.reader-adaptation-deployment.active", "true").withProperty("task.workload-authorization.enabled", "true");
    }

    @Test
    void enablesBothAtomicallyAndReplaysWithoutChangingAudit() {
        run();
        environment.setProperty("task.reader-adaptation-deployment.audit-id", "replay-v2");
        run();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM execution_cluster", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT description FROM task_definition", String.class)).endsWith("fixture-v1");
    }

    @Test
    void disablingDoesNotRequireEnabledWorkloadSigning() {
        run();
        environment.setProperty("task.reader-adaptation-deployment.active", "false");
        environment.setProperty("task.workload-authorization.enabled", "false");
        run();
        assertThat(jdbc.queryForObject("SELECT enabled FROM task_definition", Boolean.class)).isFalse();
    }

    @Test
    void rejectsWrongOrExtraStepsWithoutPartialActivation() {
        jdbc.update("UPDATE task_step_definition SET script_version=?", "unapproved");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT enabled FROM execution_cluster", Boolean.class)).isFalse();
    }

    @Test
    void rejectsMissingAuditOrWorkloadAuthorization() {
        environment.setProperty("task.workload-authorization.enabled", "false");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
        environment.setProperty("task.workload-authorization.enabled", "true");
        environment.setProperty("task.reader-adaptation-deployment.audit-id", "");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
    }

    private void run() { new AdaptationDeploymentInitializer(jdbc, manager, environment).run(null); }
}
