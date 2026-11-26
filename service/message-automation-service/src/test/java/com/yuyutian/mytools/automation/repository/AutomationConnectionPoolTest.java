package com.yuyutian.mytools.automation.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 单连接池验证运行查询不会在映射主记录时嵌套申请动作查询连接。 */
class AutomationConnectionPoolTest {
    @Test
    void shouldLoadActionsAfterReleasingRunQueryConnection() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(300);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V1__create_message_automation_schema.sql"), new ClassPathResource(
                    "db/migration/V2__add_automation_actions.sql")).execute(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            UUID runId = UUID.randomUUID();
            UUID messageId = UUID.randomUUID();
            jdbc.update("INSERT INTO automation_run (id,inbound_message_id,status,action_count,"
                    + "action_refs_json,created_at,updated_at) VALUES (?,?,'RUNNING',1,'[]',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", runId.toString(), messageId.toString());
            jdbc.update("INSERT INTO automation_action (id,automation_run_id,sequence_number,action_type,"
                    + "source_url,file_name,status,created_at,updated_at) VALUES (?,?,1,'ATTACHMENT_DOWNLOAD',"
                    + "'https://example.test/file','file','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), runId.toString());
            AutomationRepository repository = new AutomationRepository(jdbc, new ObjectMapper());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertEquals(1, repository.findRun(messageId).orElseThrow().actions().size());
                assertEquals(1, repository.findRunById(runId).orElseThrow().actions().size());
            });
        }
    }
}
