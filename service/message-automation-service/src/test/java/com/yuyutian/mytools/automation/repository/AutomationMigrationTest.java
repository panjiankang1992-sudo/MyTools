package com.yuyutian.mytools.automation.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息自动化数据库增量迁移测试。
 */
class AutomationMigrationTest {

    @Test
    void shouldUpgradeExistingV10RunToLeasedV11Schema() {
        String databaseName = "automation_migration_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("10").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run
                    (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                     action_refs_json, error_code, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, 'RUNNING', 0, '[]', NULL, ?, ?)
                """, runId.toString(), messageId.toString(), Timestamp.from(now), Timestamp.from(now));

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("11").load().migrate();

        var upgraded = jdbcTemplate.queryForMap("""
                SELECT reconcile_claimed_by, reconcile_claim_until, reconciliation_failures,
                       next_reconcile_at, last_reconciliation_error
                FROM automation_run WHERE id = ?
                """, runId.toString());
        assertThat(upgraded.get("reconcile_claimed_by")).isNull();
        assertThat(upgraded.get("reconcile_claim_until")).isNull();
        assertThat(upgraded.get("reconciliation_failures")).isEqualTo(0);
        assertThat(upgraded.get("next_reconcile_at")).isNull();
        assertThat(upgraded.get("last_reconciliation_error")).isNull();
    }

    @Test
    void shouldUpgradeExistingV11OutboxWithNonNegativePageCursor() {
        String databaseName = "automation_page_cursor_"
                + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("11").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run
                    (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                     action_refs_json, error_code, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, 'SUCCEEDED', 1, '[]', NULL, ?, ?)
                """, runId.toString(), messageId.toString(), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, payload_json, created_at, published_at)
                VALUES (?, ?, 'AutomationRunCompleted', '{}', ?, NULL)
                """, eventId.toString(), runId.toString(), Timestamp.from(now));

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("12").load().migrate();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT delivery_page_cursor FROM automation_outbox WHERE id = ?
                """, Integer.class, eventId.toString())).isZero();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE automation_outbox SET delivery_page_cursor = -1 WHERE id = ?
                """, eventId.toString())).isInstanceOf(RuntimeException.class);
    }

}
