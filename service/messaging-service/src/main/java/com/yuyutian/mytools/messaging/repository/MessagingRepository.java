package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.DeliveryRecord;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 投递、入站消息和事务 Outbox 仓储。
 */
@Repository
public class MessagingRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建消息仓储。
     */
    public MessagingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增投递请求。
     */
    public void insertDelivery(DeliveryRecord record) {
        jdbcTemplate.update("""
                INSERT INTO delivery_request
                    (id, owner_id, idempotency_key, channel_type, account_id, recipient, subject_text,
                     body_text, status, task_instance_id, provider_message_id, last_error_code,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.id().toString(), record.ownerId(), record.idempotencyKey(), record.channelType().name(),
                record.accountId() == null ? null : record.accountId().toString(), record.recipient(), record.subject(),
                record.body(), record.status(), null, null, null, Timestamp.from(record.createdAt()),
                Timestamp.from(record.updatedAt()));
        appendOutbox("DELIVERY", record.id(), "MessageDeliveryRequested",
                Map.of("deliveryId", record.id().toString(), "channelType", record.channelType().name()));
    }

    /**
     * 按幂等键查询投递。
     */
    public Optional<DeliveryRecord> findDeliveryByIdempotencyKey(long ownerId, String key) {
        return queryDelivery("WHERE owner_id = ? AND idempotency_key = ?", ownerId, key);
    }

    /**
     * 按标识查询投递。
     */
    public Optional<DeliveryRecord> findDelivery(UUID id) {
        return queryDelivery("WHERE id = ?", id.toString());
    }

    /**
     * 绑定调度任务。
     */
    public void bindTask(UUID id, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE delivery_request SET task_instance_id = ?, status = 'QUEUED', updated_at = ? WHERE id = ?
                """, taskId.toString(), Timestamp.from(Instant.now()), id.toString());
    }

    /**
     * 尝试取得投递执行权并创建尝试记录。
     *
     * @return 新尝试序号，未取得执行权时返回零
     */
    public int beginAttempt(UUID id) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE delivery_request SET status = 'SENDING', updated_at = ?
                WHERE id = ? AND status IN ('QUEUED', 'FAILED')
                """, Timestamp.from(now), id.toString());
        if (updated == 0) {
            return 0;
        }
        Integer previous = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempt WHERE delivery_request_id = ?", Integer.class, id.toString());
        int attempt = (previous == null ? 0 : previous) + 1;
        jdbcTemplate.update("""
                INSERT INTO delivery_attempt
                    (id, delivery_request_id, attempt_number, status, provider_message_id, error_code,
                     started_at, finished_at)
                VALUES (?, ?, ?, 'SENDING', NULL, NULL, ?, NULL)
                """, UUID.randomUUID().toString(), id.toString(), attempt, Timestamp.from(now));
        return attempt;
    }

    /**
     * 完成成功投递并记录 Outbox。
     */
    public void completeDelivery(UUID id, int attempt, String providerMessageId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE delivery_attempt SET status = 'DELIVERED', provider_message_id = ?, finished_at = ?
                WHERE delivery_request_id = ? AND attempt_number = ?
                """, providerMessageId, Timestamp.from(now), id.toString(), attempt);
        jdbcTemplate.update("""
                UPDATE delivery_request SET status = 'DELIVERED', provider_message_id = ?,
                    last_error_code = NULL, updated_at = ? WHERE id = ?
                """, providerMessageId, Timestamp.from(now), id.toString());
        appendOutbox("DELIVERY", id, "MessageDelivered",
                Map.of("deliveryId", id.toString(), "providerMessageId", providerMessageId));
    }

    /**
     * 记录失败投递和稳定错误类别。
     */
    public void failDelivery(UUID id, int attempt, String errorCode) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE delivery_attempt SET status = 'FAILED', error_code = ?, finished_at = ?
                WHERE delivery_request_id = ? AND attempt_number = ?
                """, errorCode, Timestamp.from(now), id.toString(), attempt);
        jdbcTemplate.update("""
                UPDATE delivery_request SET status = 'FAILED', last_error_code = ?, updated_at = ? WHERE id = ?
                """, errorCode, Timestamp.from(now), id.toString());
        appendOutbox("DELIVERY", id, "MessageDeliveryFailed",
                Map.of("deliveryId", id.toString(), "errorCode", errorCode));
    }

    /**
     * 写入或返回幂等入站消息。
     */
    public InboundMessageView saveInbound(CreateInboundMessageRequest request) {
        Optional<InboundMessageView> existing = findInbound(request.ownerId(), request.channelType(),
                request.externalMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO inbound_message
                    (id, owner_id, channel_type, external_message_id, conversation_key, sender_ref,
                     subject_text, body_text, received_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id.toString(), request.ownerId(), request.channelType().name(), request.externalMessageId(),
                request.conversationKey(), request.sender(), request.subject(), request.body(),
                Timestamp.from(request.receivedAt()), Timestamp.from(now));
        appendOutbox("MESSAGE", id, "MessageReceived", Map.of(
                "messageId", id.toString(), "ownerId", request.ownerId(),
                "channelType", request.channelType().name(), "conversationKey", request.conversationKey(),
                "sender", request.sender()));
        return new InboundMessageView(id, request.ownerId(), request.channelType(), request.externalMessageId(),
                request.conversationKey(), request.sender(), request.subject(), request.body(),
                request.receivedAt(), now);
    }

    private Optional<InboundMessageView> findInbound(long ownerId, ChannelType type, String externalId) {
        return jdbcTemplate.query("""
                SELECT * FROM inbound_message WHERE owner_id = ? AND channel_type = ? AND external_message_id = ?
                """, (resultSet, rowNumber) -> new InboundMessageView(
                UUID.fromString(resultSet.getString("id")), resultSet.getLong("owner_id"),
                ChannelType.valueOf(resultSet.getString("channel_type")),
                resultSet.getString("external_message_id"), resultSet.getString("conversation_key"),
                resultSet.getString("sender_ref"), resultSet.getString("subject_text"),
                resultSet.getString("body_text"), resultSet.getTimestamp("received_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant()), ownerId, type.name(), externalId)
                .stream().findFirst();
    }

    private Optional<DeliveryRecord> queryDelivery(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM delivery_request " + clause, (resultSet, rowNumber) -> {
            String accountId = resultSet.getString("account_id");
            String taskId = resultSet.getString("task_instance_id");
            return new DeliveryRecord(UUID.fromString(resultSet.getString("id")), resultSet.getLong("owner_id"),
                    resultSet.getString("idempotency_key"),
                    ChannelType.valueOf(resultSet.getString("channel_type")),
                    accountId == null ? null : UUID.fromString(accountId), resultSet.getString("recipient"),
                    resultSet.getString("subject_text"), resultSet.getString("body_text"),
                    resultSet.getString("status"), taskId == null ? null : UUID.fromString(taskId),
                    resultSet.getString("provider_message_id"), resultSet.getString("last_error_code"),
                    resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    private void appendOutbox(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO messaging_outbox
                        (id, aggregate_type, aggregate_id, event_type, payload_json, created_at, published_at)
                    VALUES (?, ?, ?, ?, ?, ?, NULL)
                    """, UUID.randomUUID().toString(), aggregateType, aggregateId.toString(), eventType,
                    objectMapper.writeValueAsString(payload), Timestamp.from(Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Messaging event cannot be serialized", exception);
        }
    }
}
