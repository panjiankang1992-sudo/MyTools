package com.yuyutian.mytools.task.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 流式附件协议迁移与严格参数校验回归测试。 */
class StreamedAttachmentMetadataContractTest {
    @Test
    void shouldMigrateMetadataWithoutRelaxingAttachmentBounds() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE task_definition (name VARCHAR(128), parameter_schema TEXT, "
                + "version INT, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO task_definition VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                "download_message_attachment", "{}", 7);
        jdbc.update("INSERT INTO task_definition VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                "download_http_asset", "{}", 9);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V172__carry_streamed_attachment_metadata.sql")).execute(dataSource);
        assertEquals(8, jdbc.queryForObject("SELECT version FROM task_definition WHERE "
                + "name='download_message_attachment'", Integer.class));
        assertEquals(9, jdbc.queryForObject("SELECT version FROM task_definition WHERE "
                + "name='download_http_asset'", Integer.class));
        var mapper = new ObjectMapper();
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(
                jdbc.queryForObject("SELECT parameter_schema FROM task_definition WHERE "
                        + "name='download_message_attachment'", String.class));
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("downloadRequestId", UUID.randomUUID().toString());
        parameters.put("attachmentJobId", UUID.randomUUID().toString());
        parameters.put("itemId", UUID.randomUUID().toString());
        parameters.put("fileName", "attachment.jpg");
        parameters.put("ownerId", 1);
        parameters.put("maxBytes", 21474836480L);
        // 旧客户端与当前 Messaging 元数据均必须通过同一严格协议。
        assertTrue(schema.validate(parameters).isEmpty());
        parameters.put("resourceUsername", "test-user");
        parameters.put("assetMimeType", "image/jpeg");
        parameters.put("receivedAt", "2026-10-12T01:00:00Z");
        assertTrue(schema.validate(parameters).isEmpty());
        parameters.put("unknown", true);
        assertFalse(schema.validate(parameters).isEmpty());
        parameters.remove("unknown");
        parameters.put("maxBytes", 21474836481L);
        assertFalse(schema.validate(parameters).isEmpty());
        parameters.put("maxBytes", 1024);
        parameters.put("resourceUsername", "../escape");
        assertFalse(schema.validate(parameters).isEmpty());
        parameters.put("resourceUsername", "test-user");
        parameters.put("receivedAt", "invalid");
        assertFalse(schema.validate(parameters).isEmpty());
        parameters.remove("attachmentJobId");
        assertFalse(schema.validate(parameters).isEmpty());
    }
}
