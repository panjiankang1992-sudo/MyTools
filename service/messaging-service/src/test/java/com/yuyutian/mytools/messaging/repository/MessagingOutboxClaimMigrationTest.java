package com.yuyutian.mytools.messaging.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站 Outbox 领取租约迁移测试。
 */
class MessagingOutboxClaimMigrationTest {

    @Test
    void shouldUpgradeExistingOutboxRowsWithoutChangingTheirState() {
        String databaseName = "messaging_claim_migration_"
                + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        migrate(url, MigrationVersion.fromVersion("16"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        Instant now = Instant.now();
        UUID pendingId = insertEvent(jdbcTemplate, now, null, null);
        UUID deadId = insertEvent(jdbcTemplate, now, null, now);
        UUID publishedId = insertEvent(jdbcTemplate, now, now, null);

        migrate(url, MigrationVersion.LATEST);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE claim_token IS NULL AND claim_until IS NULL
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                """, Integer.class, pendingId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE id = ? AND dead_at IS NOT NULL
                """, Integer.class, deadId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE id = ? AND published_at IS NOT NULL
                """, Integer.class, publishedId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.indexes
                WHERE LOWER(index_name) = 'idx_messaging_outbox_claim'
                """, Integer.class)).isEqualTo(1);
    }

    private void migrate(String url, MigrationVersion target) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private UUID insertEvent(JdbcTemplate jdbcTemplate, Instant createdAt,
                             Instant publishedAt, Instant deadAt) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messaging_outbox
                    (id, aggregate_type, aggregate_id, event_type, payload_json, created_at,
                     published_at, dead_at)
                VALUES (?, 'INBOUND_MESSAGE', ?, 'MessageReceived', '{}', ?, ?, ?)
                """, eventId.toString(), UUID.randomUUID().toString(), Timestamp.from(createdAt),
                publishedAt == null ? null : Timestamp.from(publishedAt),
                deadAt == null ? null : Timestamp.from(deadAt));
        return eventId;
    }
}
