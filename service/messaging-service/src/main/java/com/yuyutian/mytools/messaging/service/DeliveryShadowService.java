package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.CreateDeliveryShadowRequest;
import com.yuyutian.mytools.messaging.model.DeliveryShadowView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理无副作用的投递影子对账证据。
 */
@Service
public class DeliveryShadowService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建投递影子服务。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public DeliveryShadowService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等记录脱敏投递证据，不创建任务且不触发真实投递。
     *
     * @param request 影子请求
     * @return 影子记录
     */
    public DeliveryShadowView record(CreateDeliveryShadowRequest request) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO delivery_shadow_audit
                        (id, owner_id, idempotency_key, channel_type, recipient_hash, payload_hash,
                         legacy_outcome, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id.toString(), request.ownerId(), request.idempotencyKey(), request.channelType().name(),
                    request.recipientHash(), request.payloadHash(), request.legacyOutcome(),
                    Timestamp.from(createdAt));
            return new DeliveryShadowView(id, request.idempotencyKey(), request.channelType(),
                    request.legacyOutcome(), false, createdAt);
        } catch (DuplicateKeyException exception) {
            ShadowRecord existing = find(request.ownerId(), request.idempotencyKey());
            if (!existing.matches(request)) {
                throw new DeliveryInvalidException();
            }
            return new DeliveryShadowView(existing.id(), existing.idempotencyKey(), existing.channelType(),
                    existing.legacyOutcome(), true, existing.createdAt());
        }
    }

    private ShadowRecord find(long ownerId, String idempotencyKey) {
        List<ShadowRecord> records = jdbcTemplate.query("""
                SELECT id, idempotency_key, channel_type, recipient_hash, payload_hash, legacy_outcome, created_at
                  FROM delivery_shadow_audit
                 WHERE owner_id = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> new ShadowRecord(
                UUID.fromString(resultSet.getString("id")), resultSet.getString("idempotency_key"),
                com.yuyutian.mytools.messaging.model.ChannelType.valueOf(resultSet.getString("channel_type")),
                resultSet.getString("recipient_hash"), resultSet.getString("payload_hash"),
                resultSet.getString("legacy_outcome"), resultSet.getTimestamp("created_at").toInstant()),
                ownerId, idempotencyKey);
        if (records.isEmpty()) {
            throw new IllegalStateException("Delivery shadow replay record is missing");
        }
        return records.getFirst();
    }

    private record ShadowRecord(UUID id, String idempotencyKey,
                                com.yuyutian.mytools.messaging.model.ChannelType channelType,
                                String recipientHash, String payloadHash, String legacyOutcome, Instant createdAt) {
        private boolean matches(CreateDeliveryShadowRequest request) {
            return channelType == request.channelType() && recipientHash.equals(request.recipientHash())
                    && payloadHash.equals(request.payloadHash()) && legacyOutcome.equals(request.legacyOutcome());
        }
    }
}
