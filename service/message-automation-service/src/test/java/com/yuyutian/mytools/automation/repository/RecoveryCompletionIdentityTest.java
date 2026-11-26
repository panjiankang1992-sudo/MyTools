package com.yuyutian.mytools.automation.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仅恢复审计之后的新完成事件获得独立身份，旧通知和其他运行保持原身份。 */
class RecoveryCompletionIdentityTest {
    @Test
    void shouldScopeRecoveredIdentityToTheMatchingRunAndLaterCompletion() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1__create_message_automation_schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AutomationRepository repository = new AutomationRepository(jdbc, new ObjectMapper());
        UUID run = UUID.randomUUID();
        UUID oldEvent = UUID.randomUUID();
        UUID newEvent = UUID.randomUUID();
        Instant now = Instant.now();
        insert(jdbc, oldEvent, run, "AutomationRunCompleted", now.minusSeconds(1));
        insert(jdbc, newEvent, run, "AutomationRunCompleted", now.plusSeconds(1));
        assertFalse(repository.isRecoveredCompletion(run, newEvent));
        insert(jdbc, UUID.randomUUID(), run, "AutomationRunRecoveryRecorded", now);
        assertFalse(repository.isRecoveredCompletion(run, oldEvent));
        assertTrue(repository.isRecoveredCompletion(run, newEvent));
        assertFalse(repository.isRecoveredCompletion(UUID.randomUUID(), newEvent));
    }

    private static void insert(JdbcTemplate jdbc, UUID event, UUID run, String kind, Instant at) {
        jdbc.update("INSERT INTO automation_outbox (id,aggregate_id,event_type,payload_json,created_at) "
                + "VALUES (?,?,?,'{}',?)", event.toString(), run.toString(), kind, Timestamp.from(at));
    }
}
