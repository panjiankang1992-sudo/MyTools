package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 单连接池复现嵌套入站消息查询饥饿，校验关联数据在主查询释放连接后加载。 */
class InboundConnectionPoolTest {
    @Test
    void shouldReadMessageAndPageWithoutBorrowingNestedConnections() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(300);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/migration/V1__create_messaging_schema.sql"), new ClassPathResource(
                    "db/migration/V2__add_inbound_message_parts.sql")).execute(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("ALTER TABLE inbound_message_part ADD provider_account_key VARCHAR(128)");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO inbound_message (id,owner_id,channel_type,external_message_id,"
                    + "conversation_key,sender_ref,body_text,received_at,created_at) "
                    + "VALUES (?,1,'TELEGRAM','external','conversation','sender','body',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id.toString());
            jdbc.update("INSERT INTO inbound_message_part (id,inbound_message_id,sequence_number,"
                    + "part_type,text_content,created_at) VALUES (?,?,1,'TEXT','part',CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), id.toString());
            jdbc.update("INSERT INTO messaging_outbox (id,aggregate_type,aggregate_id,event_type,"
                    + "payload_json,created_at,published_at) VALUES (?,'MESSAGE',?,"
                    + "'OneBotForwardAccepted','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), id.toString());
            MessagingRepository repository = new MessagingRepository(jdbc, new ObjectMapper());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                var message = repository.findInbound(id).orElseThrow();
                assertEquals("part", message.parts().getFirst().text());
                assertTrue(message.preAcknowledged());
                var page = repository.listInbound(1, null, 10);
                assertEquals(1, page.items().size());
                assertEquals(1, page.items().getFirst().parts().size());
                assertTrue(repository.listInbound(1, id, 10).items().isEmpty());
            });
        }
    }
}
