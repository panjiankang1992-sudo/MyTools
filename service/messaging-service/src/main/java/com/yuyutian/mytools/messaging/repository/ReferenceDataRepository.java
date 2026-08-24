package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.LegacyKnownRecipientItem;
import com.yuyutian.mytools.messaging.model.LegacyMessageTemplateItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 消息模板、已知收件人及其迁移证据仓储。 */
@Repository
public class ReferenceDataRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 创建参考数据仓储。 */
    public ReferenceDataRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 查询旧身份迁移证据。 */
    public Optional<MigrationRecord> findMigration(String entityType, String sourceSystem,
                                                    String legacyEntityId) {
        return jdbcTemplate.query("""
                SELECT * FROM message_reference_data_migration
                WHERE entity_type=? AND source_system=? AND legacy_entity_id=?
                """, (resultSet, rowNumber) -> mapMigration(resultSet), entityType,
                sourceSystem, legacyEntityId).stream().findFirst();
    }

    /** 写入模板及其迁移证据。 */
    public void insertTemplate(String migrationKey, LegacyMessageTemplateItem item, String digest) {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO message_template
                    (id,owner_id,source_system,legacy_template_id,channel_type,template_name,
                     description_text,subject_text,body_text,body_html,variables_json,
                     legacy_created_at,legacy_updated_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, targetId.toString(), item.ownerId(), item.sourceSystem(), item.legacyTemplateId(),
                item.channelType().name(), item.name(), item.description(), item.subject(), item.bodyText(),
                item.bodyHtml(), json(item.variables()), Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()), Timestamp.from(now), Timestamp.from(now));
        insertMigration(migrationKey, "TEMPLATE", item.sourceSystem(), item.legacyTemplateId(),
                digest, targetId, now);
    }

    /** 写入已知收件人及其迁移证据。 */
    public void insertRecipient(String migrationKey, LegacyKnownRecipientItem item, String digest) {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO known_recipient
                    (id,owner_id,source_system,legacy_recipient_id,channel_type,recipient_address,
                     display_name,legacy_created_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, targetId.toString(), item.ownerId(), item.sourceSystem(), item.legacyRecipientId(),
                item.channelType().name(), item.address(), item.name(), Timestamp.from(item.createdAt()),
                Timestamp.from(now));
        insertMigration(migrationKey, "RECIPIENT", item.sourceSystem(), item.legacyRecipientId(),
                digest, targetId, now);
    }

    /** 按迁移键读取稳定排序的迁移证据。 */
    public List<MigrationRecord> findMigrations(String migrationKey) {
        return jdbcTemplate.query("""
                SELECT * FROM message_reference_data_migration WHERE migration_key=?
                ORDER BY entity_type,source_system,legacy_entity_id
                """, (resultSet, rowNumber) -> mapMigration(resultSet), migrationKey);
    }

    private void insertMigration(String migrationKey, String entityType, String sourceSystem,
                                 String legacyEntityId, String digest, UUID targetId, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO message_reference_data_migration
                    (id,migration_key,entity_type,source_system,legacy_entity_id,payload_sha256,
                     target_entity_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), migrationKey, entityType, sourceSystem,
                legacyEntityId, digest, targetId.toString(), Timestamp.from(now));
    }

    private MigrationRecord mapMigration(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new MigrationRecord(resultSet.getString("migration_key"),
                resultSet.getString("entity_type"), resultSet.getString("source_system"),
                resultSet.getString("legacy_entity_id"), resultSet.getString("payload_sha256"),
                UUID.fromString(resultSet.getString("target_entity_id")),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private String json(Object value) {
        if (value == null) { return null; }
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Reference data JSON is invalid", exception);
        }
    }

    /** 参考数据迁移证据。 */
    public record MigrationRecord(String migrationKey, String entityType, String sourceSystem,
                                  String legacyEntityId, String payloadSha256,
                                  UUID targetEntityId, Instant createdAt) {
    }
}
