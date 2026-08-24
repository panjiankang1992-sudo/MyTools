package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.LegacyOutboundMessageItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 历史发件归档仓储。 */
@Repository
public class OutboundHistoryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建归档仓储。
     *
     * @param jdbcTemplate 数据库访问器
     * @param objectMapper JSON 编解码器
     */
    public OutboundHistoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按旧身份查询迁移记录。 */
    public Optional<MigrationRecord> find(String sourceSystem, String legacyMessageId) {
        return jdbcTemplate.query("""
                SELECT migration_key, source_system, legacy_message_id, payload_sha256,
                       outbound_history_id, created_at
                FROM outbound_history_migration WHERE source_system=? AND legacy_message_id=?
                """, (resultSet, rowNumber) -> new MigrationRecord(
                resultSet.getString("migration_key"), resultSet.getString("source_system"),
                resultSet.getString("legacy_message_id"), resultSet.getString("payload_sha256"),
                UUID.fromString(resultSet.getString("outbound_history_id")),
                resultSet.getTimestamp("created_at").toInstant()), sourceSystem, legacyMessageId)
                .stream().findFirst();
    }

    /** 写入历史记录和迁移证据，不产生实时事件。 */
    public void insert(String migrationKey, LegacyOutboundMessageItem item, String payloadSha256) {
        UUID historyId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO outbound_message_history
                    (id,owner_id,source_system,legacy_message_id,channel_type,delivery_status,sender_ref,
                     recipients_json,subject_text,body_text,body_html,attachments_json,template_ref,
                     provider_message_id,error_code,sent_at,legacy_created_at,archived_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, historyId.toString(), item.ownerId(), item.sourceSystem(), item.legacyMessageId(),
                item.channelType().name(), item.status(), item.sender(), json(item.recipients()), item.subject(),
                item.bodyText(), item.bodyHtml(), json(item.attachments()), item.templateRef(),
                item.providerMessageId(), item.errorCode(), timestamp(item.sentAt()),
                Timestamp.from(item.createdAt()), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO outbound_history_migration
                    (id,migration_key,source_system,legacy_message_id,payload_sha256,outbound_history_id,created_at)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), migrationKey, item.sourceSystem(), item.legacyMessageId(),
                payloadSha256, historyId.toString(), Timestamp.from(now));
    }

    /** 按迁移键稳定读取迁移证据。 */
    public List<MigrationRecord> findAll(String migrationKey) {
        return jdbcTemplate.query("""
                SELECT migration_key,source_system,legacy_message_id,payload_sha256,
                       outbound_history_id,created_at
                FROM outbound_history_migration WHERE migration_key=?
                ORDER BY source_system,legacy_message_id
                """, (resultSet, rowNumber) -> new MigrationRecord(
                resultSet.getString("migration_key"), resultSet.getString("source_system"),
                resultSet.getString("legacy_message_id"), resultSet.getString("payload_sha256"),
                UUID.fromString(resultSet.getString("outbound_history_id")),
                resultSet.getTimestamp("created_at").toInstant()), migrationKey);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Historical message JSON is invalid", exception);
        }
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    /** 历史发件迁移证据。 */
    public record MigrationRecord(String migrationKey, String sourceSystem, String legacyMessageId,
                                  String payloadSha256, UUID historyId, Instant createdAt) {
    }
}
