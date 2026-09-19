package com.yuyutian.mytools.messaging.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.AttachmentDownloadRecord;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.DeliveryRecord;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.InboundMessagePart;
import com.yuyutian.mytools.messaging.model.InboundMessagePage;
import com.yuyutian.mytools.messaging.model.TaskExecutionFence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/**
 * 投递、入站消息和事务 Outbox 仓储。
 */
@Repository
public class MessagingRepository {

    private static final String FORWARD_UNAVAILABLE_TEXT = "[forward unavailable]";
    private static final String MESSAGE_RECEIVED_EVENT = "MessageReceived";
    private static final String ONEBOT_FORWARD_ACCEPTED_EVENT = "OneBotForwardAccepted";
    private static final String ONEBOT_FORWARD_ACCEPTANCE_BODY =
            "Received. Forwarded content has been stored and is being analyzed.";
    private static final int MAXIMUM_INBOUND_OUTBOX_CLAIM_BATCH_SIZE = 200;
    private static final Duration MAXIMUM_INBOUND_OUTBOX_CLAIM_LEASE = Duration.ofMinutes(5);
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
     * 请求取消投递。
     *
     * @param id 投递标识
     */
    public void requestDeliveryCancel(UUID id) {
        Instant now=Instant.now();
        int cancelled=jdbcTemplate.update("UPDATE delivery_request SET status='CANCELLED',updated_at=? WHERE id=? AND status IN ('ACCEPTED','QUEUED','FAILED')",Timestamp.from(now),id.toString());
        if(cancelled==0)jdbcTemplate.update("UPDATE delivery_request SET status='CANCELLING',updated_at=? WHERE id=? AND status='SENDING'",Timestamp.from(now),id.toString());
    }

    /**
     * 尝试取得投递执行权并创建尝试记录。
     *
     * @param id 投递标识
     * @param fence 任务执行隔离上下文
     * @return 新尝试序号，未取得执行权时返回零
     */
    public int beginAttempt(UUID id, TaskExecutionFence fence) {
        Instant now = Instant.now();
        if (isStaleFence(id, fence)) {
            return 0;
        }
        int updated = jdbcTemplate.update("""
                UPDATE delivery_request SET status = 'SENDING', updated_at = ?
                WHERE id = ? AND status IN ('QUEUED', 'FAILED')
                """, Timestamp.from(now), id.toString());
        if (updated == 0) {
            return 0;
        }
        storeFence(id, fence, now);
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
     * 在已投递结果上校验并推进单调隔离令牌。
     *
     * @param id 投递标识
     * @param fence 执行隔离上下文
     * @return 是否接受
     */
    public boolean acquireDeliveredFence(UUID id, TaskExecutionFence fence) {
        if (isStaleFence(id, fence)) {
            return false;
        }
        storeFence(id, fence, Instant.now());
        return true;
    }

    private boolean isStaleFence(UUID id, TaskExecutionFence fence) {
        return jdbcTemplate.query("""
                SELECT task_instance_id, step_name, business_key, fencing_token
                FROM delivery_execution_fence WHERE delivery_request_id = ? FOR UPDATE
                """, (resultSet, rowNumber) -> new StoredFence(
                UUID.fromString(resultSet.getString("task_instance_id")), resultSet.getString("step_name"),
                resultSet.getString("business_key"), resultSet.getLong("fencing_token")), id.toString())
                .stream().findFirst()
                .map(stored -> stored.fencingToken() > fence.fencingToken()
                        || (stored.fencingToken() == fence.fencingToken()
                        && (!stored.taskInstanceId().equals(fence.taskInstanceId())
                        || !stored.stepName().equals(fence.stepName())
                        || !stored.businessKey().equals(fence.businessKey()))))
                .orElse(false);
    }

    private void storeFence(UUID id, TaskExecutionFence fence, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE delivery_execution_fence
                SET task_instance_id = ?, step_name = ?, business_key = ?, fencing_token = ?, updated_at = ?
                WHERE delivery_request_id = ? AND fencing_token <= ?
                """, fence.taskInstanceId().toString(), fence.stepName(), fence.businessKey(), fence.fencingToken(),
                Timestamp.from(now), id.toString(), fence.fencingToken());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO delivery_execution_fence
                    (delivery_request_id, task_instance_id, step_name, business_key, fencing_token,
                     created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id.toString(), fence.taskInstanceId().toString(), fence.stepName(), fence.businessKey(),
                    fence.fencingToken(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private record StoredFence(UUID taskInstanceId, String stepName, String businessKey, long fencingToken) {
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
        return saveInbound(request, true);
    }

    /**
     * 写入历史入站消息但不产生实时 MessageReceived 事件。
     *
     * @param request 标准消息
     * @return 幂等消息视图
     */
    public InboundMessageView saveHistoricalInbound(CreateInboundMessageRequest request) {
        return saveInbound(request, false);
    }

    /**
     * 先持久化 OneBot 原始事件与合并转发引用，待后台展开后再发布实时事件。
     *
     * @param request 标准消息
     * @param rawEvent 原始 OneBot 事件
     * @param accountKey OneBot 账户标识
     * @param forwardIds 合并转发引用
     * @param truncated 是否因安全预算截断
     * @return 幂等消息视图
     */
    public InboundMessageView saveOneBotInbound(CreateInboundMessageRequest request, String rawEvent,
                                                String accountKey, List<String> forwardIds, boolean truncated) {
        Optional<InboundMessageView> existing = findInbound(request.ownerId(), request.channelType(),
                request.externalMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Set<String> normalizedForwardIds = new LinkedHashSet<>();
        for (String forwardId : forwardIds) {
            if (forwardId != null && !forwardId.isBlank() && normalizedForwardIds.size() < 8) {
                normalizedForwardIds.add(forwardId.substring(0, Math.min(forwardId.length(), 512)));
            }
        }
        if (normalizedForwardIds.isEmpty() && !truncated) {
            return insertInbound(request, true);
        }
        InboundMessageView message = insertInbound(request, false);
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO onebot_inbound_processing
                    (inbound_message_id, account_key, raw_event_json, status, has_failures,
                     notified_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, NULL, ?, ?)
                """, message.id().toString(), accountKey, rawEvent, truncated,
                Timestamp.from(now), Timestamp.from(now));
        insertOneBotForwardJobs(message.id(), normalizedForwardIds, now);
        if (!normalizedForwardIds.isEmpty()) {
            // 独立受理事件不会触发自动化，只用于尽快向原会话确认合并转发已经可靠落库。
            String idempotencyKey = "onebot-forward-accepted:" + message.id();
            appendOutbox("MESSAGE", message.id(), ONEBOT_FORWARD_ACCEPTED_EVENT,
                    Map.of("messageId", message.id().toString(), "idempotencyKey", idempotencyKey,
                            "body", ONEBOT_FORWARD_ACCEPTANCE_BODY));
        }
        if (normalizedForwardIds.isEmpty()) {
            publishOneBotInboundIfTerminal(message.id());
        }
        return message;
    }

    private InboundMessageView saveInbound(CreateInboundMessageRequest request, boolean emitRealtimeEvent) {
        Optional<InboundMessageView> existing = findInbound(request.ownerId(), request.channelType(),
                request.externalMessageId());
        if (existing.isPresent()) {
            return existing.get();
        }
        return insertInbound(request, emitRealtimeEvent);
    }

    private InboundMessageView insertInbound(CreateInboundMessageRequest request, boolean emitRealtimeEvent) {
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
        insertInboundParts(id, request.parts(), now);
        if (emitRealtimeEvent) {
            appendOutbox("MESSAGE", id, MESSAGE_RECEIVED_EVENT, Map.of(
                    "messageId", id.toString(), "ownerId", request.ownerId(),
                    "channelType", request.channelType().name(), "conversationKey", request.conversationKey(),
                    "sender", request.sender()));
        }
        return new InboundMessageView(id, request.ownerId(), request.channelType(), request.externalMessageId(),
                request.conversationKey(), request.sender(), request.subject(), request.body(),
                request.receivedAt(), now, findInboundParts(id), false);
    }

    /**
     * 查询历史消息迁移审计记录。
     *
     * @param sourceSystem 来源系统
     * @param legacyMessageId 旧消息标识
     * @return 迁移记录
     */
    public Optional<HistoryMigrationRecord> findHistoryMigration(String sourceSystem, String legacyMessageId) {
        return jdbcTemplate.query("""
                SELECT * FROM inbound_history_migration
                WHERE source_system = ? AND legacy_message_id = ?
                """, (resultSet, rowNumber) -> new HistoryMigrationRecord(
                resultSet.getString("migration_key"), resultSet.getString("source_system"),
                resultSet.getString("legacy_message_id"), resultSet.getString("payload_sha256"),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getTimestamp("created_at").toInstant()), sourceSystem, legacyMessageId)
                .stream().findFirst();
    }

    /**
     * 新增历史消息迁移审计记录。
     *
     * @param record 迁移记录
     */
    public void insertHistoryMigration(HistoryMigrationRecord record) {
        jdbcTemplate.update("""
                INSERT INTO inbound_history_migration
                    (id, migration_key, source_system, legacy_message_id, payload_sha256,
                     inbound_message_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), record.migrationKey(), record.sourceSystem(),
                record.legacyMessageId(), record.payloadSha256(), record.inboundMessageId().toString(),
                Timestamp.from(record.createdAt()));
    }

    /**
     * 按迁移键读取稳定排序的历史映射证据。
     *
     * @param migrationKey 迁移键
     * @return 历史映射记录
     */
    public List<HistoryMigrationRecord> findHistoryMigrations(String migrationKey) {
        return jdbcTemplate.query("""
                SELECT * FROM inbound_history_migration WHERE migration_key = ?
                ORDER BY source_system, legacy_message_id
                """, (resultSet, rowNumber) -> new HistoryMigrationRecord(
                resultSet.getString("migration_key"), resultSet.getString("source_system"),
                resultSet.getString("legacy_message_id"), resultSet.getString("payload_sha256"),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getTimestamp("created_at").toInstant()), migrationKey);
    }

    /**
     * 历史消息迁移审计记录。
     *
     * @param migrationKey 迁移键
     * @param sourceSystem 来源系统
     * @param legacyMessageId 旧消息标识
     * @param payloadSha256 内容摘要
     * @param inboundMessageId 新消息标识
     * @param createdAt 创建时间
     */
    public record HistoryMigrationRecord(String migrationKey, String sourceSystem, String legacyMessageId,
                                         String payloadSha256, UUID inboundMessageId, Instant createdAt) {
    }

    /**
     * 按标识查询标准化入站消息。
     *
     * @param id 消息标识
     * @return 入站消息
     */
    public Optional<InboundMessageView> findInbound(UUID id) {
        return jdbcTemplate.query("SELECT * FROM inbound_message WHERE id = ?", (resultSet, rowNumber) ->
                mapInbound(resultSet), id.toString()).stream().findFirst();
    }

    /**
     * 按所有者分页查询入站消息。
     *
     * @param ownerId 所有者
     * @param afterId 游标消息
     * @param limit 数量
     * @return 消息页
     */
    public InboundMessagePage listInbound(long ownerId, UUID afterId, int limit) {
        Instant afterTime;
        if (afterId != null) {
            afterTime = jdbcTemplate.query("SELECT received_at FROM inbound_message WHERE id=? AND owner_id=?",
                    (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(), afterId.toString(), ownerId)
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("inbound cursor not found"));
        } else { afterTime = null; }
        List<InboundMessageView> values = afterId == null
                ? jdbcTemplate.query("SELECT * FROM inbound_message WHERE owner_id=? ORDER BY received_at DESC,id DESC LIMIT ?",
                    (resultSet, rowNumber) -> mapInbound(resultSet), ownerId, limit + 1)
                : jdbcTemplate.query("SELECT * FROM inbound_message WHERE owner_id=? AND (received_at<? OR (received_at=? AND id<?)) ORDER BY received_at DESC,id DESC LIMIT ?",
                    (resultSet, rowNumber) -> mapInbound(resultSet), ownerId, Timestamp.from(afterTime),
                    Timestamp.from(afterTime), afterId.toString(), limit + 1);
        List<InboundMessageView> page = values.stream().limit(limit).toList();
        UUID next = values.size() > limit && !page.isEmpty() ? page.getLast().id() : null;
        return new InboundMessagePage(page, next);
    }

    /**
     * 查询到期或租约过期的 OneBot 合并转发展开任务。
     *
     * @param limit 批次上限
     * @return 待处理任务
     */
    public List<OneBotForwardExpansion> findDueOneBotForwardExpansions(int limit) {
        return jdbcTemplate.query("""
                SELECT f.id, f.inbound_message_id, p.account_key, f.forward_id, f.attempt_count
                FROM onebot_forward_expansion f
                JOIN onebot_inbound_processing p ON p.inbound_message_id = f.inbound_message_id
                WHERE p.status = 'PENDING' AND (
                    (f.status = 'PENDING' AND (f.next_attempt_at IS NULL OR f.next_attempt_at <= CURRENT_TIMESTAMP(6)))
                    OR (f.status = 'IN_FLIGHT' AND f.lease_expires_at <= CURRENT_TIMESTAMP(6))
                )
                ORDER BY f.created_at, f.id LIMIT ?
                """, (resultSet, rowNumber) -> new OneBotForwardExpansion(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getString("account_key"), resultSet.getString("forward_id"),
                resultSet.getInt("attempt_count")), Math.max(1, Math.min(limit, 50)));
    }

    /**
     * 使用唯一令牌租用一个合并转发展开任务。
     *
     * @param id 任务标识
     * @param token 尝试令牌
     * @param leaseExpiresAt 租约到期时间
     * @return 是否取得执行权
     */
    public boolean claimOneBotForwardExpansion(UUID id, UUID token, Instant leaseExpiresAt) {
        return jdbcTemplate.update("""
                UPDATE onebot_forward_expansion
                SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1,
                    attempt_token = ?, lease_expires_at = ?, updated_at = ?
                WHERE id = ? AND (
                    (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(6)))
                    OR (status = 'IN_FLIGHT' AND lease_expires_at <= CURRENT_TIMESTAMP(6))
                )
                """, token.toString(), Timestamp.from(leaseExpiresAt), Timestamp.from(Instant.now()),
                id.toString()) == 1;
    }

    /**
     * 提交一次成功的合并转发展开结果。
     *
     * @param expansion 展开任务
     * @param token 尝试令牌
     * @param parts 新解析出的消息分段
     * @param nestedForwardIds 新发现的合并转发引用
     * @param truncated 是否因安全预算截断
     * @return 是否接受该尝试结果
     */
    public boolean completeOneBotForwardExpansion(OneBotForwardExpansion expansion, UUID token,
                                                  List<CreateInboundMessagePart> parts,
                                                  List<String> nestedForwardIds, boolean truncated) {
        lockOneBotInboundProcessing(expansion.messageId());
        int updated = jdbcTemplate.update("""
                UPDATE onebot_forward_expansion
                SET status = 'SUCCEEDED', attempt_token = NULL, lease_expires_at = NULL,
                    next_attempt_at = NULL, last_error_code = NULL, updated_at = ?
                WHERE id = ? AND status = 'IN_FLIGHT' AND attempt_token = ?
                """, Timestamp.from(Instant.now()), expansion.id().toString(), token.toString());
        if (updated != 1) {
            return false;
        }
        boolean storageTruncated = appendExpandedOneBotParts(expansion.messageId(), parts);
        if (truncated || storageTruncated) {
            jdbcTemplate.update("""
                    UPDATE onebot_inbound_processing SET has_failures = TRUE, updated_at = ?
                    WHERE inbound_message_id = ?
                    """, Timestamp.from(Instant.now()), expansion.messageId().toString());
        }
        insertOneBotForwardJobs(expansion.messageId(), new LinkedHashSet<>(nestedForwardIds), Instant.now());
        publishOneBotInboundIfTerminal(expansion.messageId());
        return true;
    }

    /**
     * 记录一次有界失败；不可重试或耗尽时停止自动重试，并在所有引用终态后发布显式失败结果。
     *
     * @param expansion 展开任务
     * @param token 尝试令牌
     * @param maximumAttempts 最大尝试次数
     * @param retryable 是否可重试
     * @param errorCode 稳定错误类别
     * @return 是否进入终止状态
     */
    public boolean failOneBotForwardExpansion(OneBotForwardExpansion expansion, UUID token,
                                              int maximumAttempts, boolean retryable, String errorCode) {
        lockOneBotInboundProcessing(expansion.messageId());
        int nextAttempt = expansion.attemptCount() + 1;
        boolean exhausted = !retryable || nextAttempt >= Math.max(1, maximumAttempts);
        long delaySeconds = Math.min(60L, 1L << Math.min(Math.max(0, nextAttempt - 1), 6));
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE onebot_forward_expansion
                SET status = ?, attempt_token = NULL, lease_expires_at = NULL,
                    next_attempt_at = ?, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status = 'IN_FLIGHT' AND attempt_token = ?
                """, exhausted ? "DEAD" : "PENDING",
                exhausted ? null : Timestamp.from(now.plusSeconds(delaySeconds)), safeErrorCode(errorCode),
                Timestamp.from(now), expansion.id().toString(), token.toString());
        if (updated == 1 && exhausted) {
            publishOneBotInboundIfTerminal(expansion.messageId());
        }
        return updated == 1 && exhausted;
    }

    private void lockOneBotInboundProcessing(UUID messageId) {
        jdbcTemplate.queryForObject("""
                SELECT inbound_message_id FROM onebot_inbound_processing
                WHERE inbound_message_id = ? FOR UPDATE
                """, String.class, messageId.toString());
    }

    private void insertOneBotForwardJobs(UUID messageId, Set<String> forwardIds, Instant now) {
        int existingCount = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM onebot_forward_expansion WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).orElse(0);
        if (forwardIds.isEmpty()) {
            return;
        }
        Set<String> existingIds = new LinkedHashSet<>(jdbcTemplate.query(
                "SELECT forward_id FROM onebot_forward_expansion WHERE inbound_message_id = ?",
                (resultSet, rowNumber) -> resultSet.getString("forward_id"), messageId.toString()));
        for (String value : forwardIds) {
            String forwardId = value == null ? "" : value.strip();
            if (forwardId.isBlank()) {
                continue;
            }
            forwardId = forwardId.substring(0, Math.min(forwardId.length(), 512));
            if (!existingIds.add(forwardId)) {
                continue;
            }
            if (existingCount >= 8) {
                jdbcTemplate.update("""
                        UPDATE onebot_inbound_processing SET has_failures = TRUE, updated_at = ?
                        WHERE inbound_message_id = ?
                        """, Timestamp.from(now), messageId.toString());
                break;
            }
            jdbcTemplate.update("""
                    INSERT INTO onebot_forward_expansion
                        (id, inbound_message_id, forward_id, status, attempt_count, attempt_token,
                         next_attempt_at, lease_expires_at, last_error_code, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', 0, NULL, ?, NULL, NULL, ?, ?)
                    """, UUID.randomUUID().toString(), messageId.toString(), forwardId,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            existingCount++;
        }
    }

    private boolean appendExpandedOneBotParts(UUID messageId, List<CreateInboundMessagePart> parts) {
        Integer storedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbound_message_part WHERE inbound_message_id = ?",
                Integer.class, messageId.toString());
        int remaining = Math.max(0, 500 - (storedCount == null ? 0 : storedCount));
        Set<String> attachmentKeys = new LinkedHashSet<>(jdbcTemplate.query("""
                SELECT provider_account_key, provider_file_id, source_url FROM inbound_message_part
                WHERE inbound_message_id = ? AND part_type = 'ATTACHMENT'
                """, (resultSet, rowNumber) -> attachmentKey(resultSet.getString("provider_account_key"),
                resultSet.getString("provider_file_id"), resultSet.getString("source_url")),
                messageId.toString()));
        Set<String> textValues = new LinkedHashSet<>(jdbcTemplate.query("""
                SELECT text_content FROM inbound_message_part
                WHERE inbound_message_id = ? AND part_type = 'TEXT' AND text_content IS NOT NULL
                """, (resultSet, rowNumber) -> resultSet.getString("text_content"), messageId.toString()));
        List<CreateInboundMessagePart> additions = new ArrayList<>();
        boolean truncated = false;
        for (CreateInboundMessagePart part : parts) {
            if ("ATTACHMENT".equals(part.type())) {
                if (attachmentKeys.add(attachmentKey(part.providerAccountKey(), part.providerFileId(),
                        part.sourceUrl()))) {
                    if (additions.size() < remaining) {
                        additions.add(part);
                    } else {
                        truncated = true;
                    }
                }
            } else if ("TEXT".equals(part.type()) && part.text() != null
                    && !"[forward]".equals(part.text()) && textValues.add(part.text())) {
                if (additions.size() < remaining) {
                    additions.add(part);
                } else {
                    truncated = true;
                }
            }
        }
        if (additions.isEmpty()) {
            return truncated;
        }
        Integer maximumSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), 0) FROM inbound_message_part WHERE inbound_message_id = ?",
                Integer.class, messageId.toString());
        insertInboundParts(messageId, additions, Instant.now(), maximumSequence == null ? 0 : maximumSequence);
        List<String> newTexts = additions.stream().filter(part -> "TEXT".equals(part.type()))
                .map(CreateInboundMessagePart::text).toList();
        if (!newTexts.isEmpty()) {
            String currentBody = jdbcTemplate.queryForObject(
                    "SELECT body_text FROM inbound_message WHERE id = ?", String.class, messageId.toString());
            String expandedText = String.join("\n", newTexts);
            String combined = "[forward]".equals(currentBody) ? expandedText : currentBody + "\n" + expandedText;
            jdbcTemplate.update("UPDATE inbound_message SET body_text = ? WHERE id = ?",
                    combined.substring(0, Math.min(combined.length(), 10_485_760)), messageId.toString());
        }
        return truncated;
    }

    private String attachmentKey(String accountKey, String providerFileId, String sourceUrl) {
        if (providerFileId != null && !providerFileId.isBlank()) {
            return "provider:" + (accountKey == null ? "" : accountKey) + ":" + providerFileId;
        }
        return "url:" + (sourceUrl == null ? "" : sourceUrl);
    }

    private boolean publishOneBotInboundIfTerminal(UUID messageId) {
        Integer unfinished = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onebot_forward_expansion
                WHERE inbound_message_id = ? AND status NOT IN ('SUCCEEDED', 'DEAD')
                """, Integer.class, messageId.toString());
        if (unfinished == null || unfinished > 0) {
            return false;
        }
        Integer failures = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onebot_forward_expansion
                WHERE inbound_message_id = ? AND status = 'DEAD'
                """, Integer.class, messageId.toString());
        Boolean truncated = jdbcTemplate.queryForObject("""
                SELECT has_failures FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, Boolean.class, messageId.toString());
        boolean failed = (failures != null && failures > 0) || Boolean.TRUE.equals(truncated);
        if (failed) {
            appendExpandedOneBotParts(messageId, List.of(new CreateInboundMessagePart(
                    "TEXT", FORWARD_UNAVAILABLE_TEXT, null, null, null, null, null, null, null)));
            appendOneBotForwardWarningBody(messageId);
            // 零序号保证失败提示不会被附件动作预算截掉；该类型只会创建本地终态作业。
            insertOneBotForwardFailurePart(messageId);
        }
        Integer attachmentCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inbound_message_part
                WHERE inbound_message_id = ? AND part_type = 'ATTACHMENT'
                  AND attachment_type <> 'FORWARD_ERROR'
                """, Integer.class, messageId.toString());
        String processingStatus = failed
                ? (attachmentCount != null && attachmentCount > 0 ? "PARTIAL_FAILED" : "FAILED") : "READY";
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE onebot_inbound_processing SET status = ?, notified_at = ?, updated_at = ?
                WHERE inbound_message_id = ? AND status = 'PENDING' AND notified_at IS NULL
                """, processingStatus, Timestamp.from(now), Timestamp.from(now), messageId.toString());
        if (updated != 1) {
            return false;
        }
        InboundEventFields fields = jdbcTemplate.query("""
                SELECT owner_id, channel_type, conversation_key, sender_ref
                FROM inbound_message WHERE id = ?
                """, (resultSet, rowNumber) -> new InboundEventFields(resultSet.getLong("owner_id"),
                resultSet.getString("channel_type"), resultSet.getString("conversation_key"),
                resultSet.getString("sender_ref")), messageId.toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Inbound message is unavailable"));
        appendOutbox("MESSAGE", messageId, MESSAGE_RECEIVED_EVENT, Map.of(
                "messageId", messageId.toString(), "ownerId", fields.ownerId(),
                "channelType", fields.channelType(), "conversationKey", fields.conversationKey(),
                "sender", fields.sender()));
        return true;
    }

    private void appendOneBotForwardWarningBody(UUID messageId) {
        String currentBody = jdbcTemplate.queryForObject(
                "SELECT body_text FROM inbound_message WHERE id = ?", String.class, messageId.toString());
        if (currentBody == null || currentBody.contains(FORWARD_UNAVAILABLE_TEXT)) {
            return;
        }
        if ("[forward]".equals(currentBody)) {
            jdbcTemplate.update("UPDATE inbound_message SET body_text = ? WHERE id = ?",
                    FORWARD_UNAVAILABLE_TEXT, messageId.toString());
            return;
        }
        int maximumPrefixLength = 10_485_760 - FORWARD_UNAVAILABLE_TEXT.length() - 1;
        String combined = currentBody.substring(0, Math.min(currentBody.length(), maximumPrefixLength))
                + "\n" + FORWARD_UNAVAILABLE_TEXT;
        jdbcTemplate.update("UPDATE inbound_message SET body_text = ? WHERE id = ?",
                combined, messageId.toString());
    }

    private void insertOneBotForwardFailurePart(UUID messageId) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inbound_message_part
                WHERE inbound_message_id = ? AND attachment_type = 'FORWARD_ERROR'
                """, Integer.class, messageId.toString());
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO inbound_message_part
                    (id, inbound_message_id, sequence_number, part_type, text_content, attachment_type,
                     provider_file_id, provider_account_key, source_url, file_name, mime_type,
                     declared_size, created_at)
                VALUES (?, ?, 0, 'ATTACHMENT', NULL, 'FORWARD_ERROR', NULL, NULL, NULL,
                        'forward-unavailable', NULL, NULL, ?)
                """, UUID.randomUUID().toString(), messageId.toString(), Timestamp.from(Instant.now()));
    }

    /**
     * 一个已持久化的 OneBot 合并转发展开任务。
     *
     * @param id 任务标识
     * @param messageId 消息标识
     * @param accountKey OneBot 账户标识
     * @param forwardId 合并转发标识
     * @param attemptCount 已开始尝试次数
     */
    public record OneBotForwardExpansion(UUID id, UUID messageId, String accountKey, String forwardId,
                                         int attemptCount) {
    }

    private record InboundEventFields(long ownerId, String channelType, String conversationKey, String sender) {
    }

    /**
     * 查询附件下载所需的最小消息分段快照。
     */
    public Optional<AttachmentSource> findAttachmentSource(UUID messageId, UUID partId) {
        return jdbcTemplate.query("""
                SELECT m.owner_id, m.channel_type, p.id, p.part_type, p.attachment_type, p.provider_file_id,
                       p.provider_account_key, p.source_url, p.file_name, p.mime_type, p.declared_size,
                       m.received_at,
                       j.resolved_source_url, j.resolution_mode
                FROM inbound_message_part p JOIN inbound_message m ON m.id = p.inbound_message_id
                LEFT JOIN attachment_download_job j ON j.message_part_id = p.id
                WHERE m.id = ? AND p.id = ?
                """, (resultSet, rowNumber) -> {
            long size = resultSet.getLong("declared_size");
            boolean sizeMissing = resultSet.wasNull();
            return new AttachmentSource(resultSet.getLong("owner_id"), resultSet.getString("channel_type"),
                    UUID.fromString(resultSet.getString("id")), resultSet.getString("part_type"),
                    resultSet.getString("attachment_type"), resultSet.getString("provider_file_id"),
                    resultSet.getString("provider_account_key"), resultSet.getString("source_url"),
                    resultSet.getString("resolved_source_url"), resultSet.getString("resolution_mode"),
                    resultSet.getString("file_name"), resultSet.getString("mime_type"),
                    sizeMissing ? null : size, resultSet.getTimestamp("received_at").toInstant());
        }, messageId.toString(), partId.toString()).stream().findFirst();
    }

    /**
     * 按消息分段查询附件任务。
     */
    public Optional<AttachmentDownloadRecord> findAttachmentJobByPart(UUID partId) {
        return queryAttachmentJob("WHERE message_part_id = ?", partId.toString());
    }

    /**
     * 按标识查询附件任务。
     */
    public Optional<AttachmentDownloadRecord> findAttachmentJob(UUID jobId) {
        return queryAttachmentJob("WHERE id = ?", jobId.toString());
    }

    /**
     * 新增附件下载任务记录。
     */
    public void insertAttachmentJob(AttachmentDownloadRecord record) {
        jdbcTemplate.update("""
                INSERT INTO attachment_download_job
                    (id, inbound_message_id, message_part_id, status, task_instance_id, download_request_id,
                     last_error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """, record.id().toString(), record.messageId().toString(), record.partId().toString(),
                record.status(), record.lastErrorCode(), Timestamp.from(record.createdAt()),
                Timestamp.from(record.updatedAt()));
    }

    /**
     * 绑定附件处理的 Scheduler 任务。
     */
    public void bindAttachmentTask(UUID jobId, UUID taskId) {
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET task_instance_id = ?,
                    status = CASE WHEN status IN ('CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN status ELSE 'QUEUED' END, updated_at = ?
                WHERE id = ? AND task_instance_id IS NULL
                """, taskId.toString(), Timestamp.from(Instant.now()), jobId.toString());
    }

    /**
     * 记录已创建的 Download Ingestion 子任务。
     */
    public boolean bindDownloadRequest(UUID jobId, UUID downloadRequestId) {
        Instant now = Instant.now();
        int accepted = jdbcTemplate.update("""
                UPDATE attachment_download_job SET download_request_id = ?,
                    status = CASE WHEN status IN ('ACCEPTED', 'QUEUED', 'RESOLVED')
                        THEN 'SUBMITTED' ELSE status END,
                    last_error_code = CASE WHEN status IN ('ACCEPTED', 'QUEUED', 'RESOLVED')
                        THEN NULL ELSE last_error_code END, updated_at = ?
                WHERE id = ? AND status NOT IN ('CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                    AND (download_request_id IS NULL OR download_request_id = ?)
                """, downloadRequestId.toString(), Timestamp.from(now), jobId.toString(),
                downloadRequestId.toString());
        if (accepted > 0) {
            return true;
        }
        // 即使状态已经拒绝启动，也记录刚创建的下游标识，保证取消补偿可重试和可对账。
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET download_request_id = ?, updated_at = ?
                WHERE id = ? AND (download_request_id IS NULL OR download_request_id = ?)
                """, downloadRequestId.toString(), Timestamp.from(now), jobId.toString(),
                downloadRequestId.toString());
        return false;
    }

    /**
     * 保存渠道文件解析结果。
     */
    public void bindResolvedSource(UUID jobId, String mode, String sourceUrl) {
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET resolution_mode = ?, resolved_source_url = ?, resolved_at = ?,
                    status = CASE WHEN status IN ('CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN status ELSE 'RESOLVED' END,
                    last_error_code = CASE WHEN status IN ('CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
                        THEN last_error_code ELSE NULL END, updated_at = ?
                WHERE id = ? AND (resolution_mode IS NULL OR
                    (resolution_mode = ? AND (resolved_source_url IS NULL OR resolved_source_url = ?)))
                """, mode, sourceUrl, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                jobId.toString(), mode, sourceUrl);
    }

    /**
     * 对账更新附件下载任务状态。
     */
    public void updateAttachmentJobStatus(UUID jobId, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET status = ?, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                    AND (status <> 'CANCELLING' OR ? IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
                """, status, errorCode, Timestamp.from(Instant.now()), jobId.toString(), status);
    }

    /**
     * 原子持久化附件取消意图，终态保持不变。
     *
     * @param jobId 附件作业标识
     */
    public void requestAttachmentCancellation(UUID jobId) {
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET status = 'CANCELLING', last_error_code = NULL, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, Timestamp.from(Instant.now()), jobId.toString());
    }

    /**
     * 原子写入下游取消结果，禁止覆盖并发产生的终态。
     *
     * @param jobId 附件作业标识
     * @param status 下游映射状态
     * @param errorCode 稳定错误码
     */
    public void updateAttachmentCancellationResult(UUID jobId, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE attachment_download_job SET status = ?, last_error_code = ?, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, status, errorCode, Timestamp.from(Instant.now()), jobId.toString());
    }

    private Optional<AttachmentDownloadRecord> queryAttachmentJob(String clause, Object... arguments) {
        return jdbcTemplate.query("SELECT * FROM attachment_download_job " + clause, (resultSet, rowNumber) -> {
            String taskId = resultSet.getString("task_instance_id");
            String downloadId = resultSet.getString("download_request_id");
            return new AttachmentDownloadRecord(UUID.fromString(resultSet.getString("id")),
                    UUID.fromString(resultSet.getString("inbound_message_id")),
                    UUID.fromString(resultSet.getString("message_part_id")), resultSet.getString("status"),
                    taskId == null ? null : UUID.fromString(taskId),
                    downloadId == null ? null : UUID.fromString(downloadId),
                    resultSet.getString("last_error_code"), resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, arguments).stream().findFirst();
    }

    /**
     * 附件下载所需的不可变来源快照。
     */
    public record AttachmentSource(long ownerId, String channelType, UUID partId, String partType, String attachmentType,
                                   String providerFileId, String providerAccountKey, String sourceUrl,
                                   String resolvedSourceUrl, String resolutionMode, String fileName,
                                   String mimeType, Long declaredSize, Instant receivedAt) {
    }

    /**
     * 原子领取一批等待转发的入站消息 Outbox 事件。
     *
     * @param limit 批次上限
     * @param claimLease 租约时长
     * @return 当前调用独占的待发布事件
     */
    @Transactional
    public List<OutboxEvent> claimUnpublishedInboundEvents(int limit, Duration claimLease) {
        int claimLimit = boundedInboundOutboxClaimLimit(limit);
        if (claimLimit == 0) {
            return List.of();
        }
        Duration boundedClaimLease = boundedInboundOutboxClaimLease(claimLease);
        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        List<OutboxEvent> events = jdbcTemplate.query("""
                SELECT terminal.id, terminal.aggregate_id FROM messaging_outbox terminal
                WHERE terminal.published_at IS NULL AND terminal.dead_at IS NULL
                  AND terminal.event_type = 'MessageReceived'
                  AND (terminal.next_attempt_at IS NULL OR terminal.next_attempt_at <= CURRENT_TIMESTAMP(6))
                  AND (terminal.claim_until IS NULL OR terminal.claim_until <= ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM messaging_outbox accepted
                      WHERE accepted.aggregate_id = terminal.aggregate_id
                        AND accepted.event_type = 'OneBotForwardAccepted'
                        AND accepted.published_at IS NULL AND accepted.dead_at IS NULL
                  )
                ORDER BY terminal.created_at, terminal.id LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new OutboxEvent(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("aggregate_id")), claimToken), Timestamp.from(now), claimLimit);
        claimInboundOutboxEvents(events.stream().map(OutboxEvent::id).toList(), claimToken, now,
                boundedClaimLease);
        return List.copyOf(events);
    }

    /**
     * 原子领取一批等待回复的 OneBot 合并转发受理事件。
     *
     * @param limit 批次上限
     * @param claimLease 租约时长
     * @return 当前调用独占的待回复事件
     */
    @Transactional
    public List<OneBotAcceptanceEvent> claimUnpublishedOneBotAcceptanceEvents(int limit,
                                                                               Duration claimLease) {
        int claimLimit = boundedInboundOutboxClaimLimit(limit);
        if (claimLimit == 0) {
            return List.of();
        }
        Duration boundedClaimLease = boundedInboundOutboxClaimLease(claimLease);
        Instant now = Instant.now();
        String claimToken = UUID.randomUUID().toString();
        List<OneBotAcceptanceEvent> events = jdbcTemplate.query("""
                SELECT id, aggregate_id, payload_json FROM messaging_outbox
                WHERE published_at IS NULL AND dead_at IS NULL AND event_type = 'OneBotForwardAccepted'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(6))
                  AND (claim_until IS NULL OR claim_until <= ?)
                ORDER BY created_at, id LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> mapOneBotAcceptanceEvent(resultSet.getString("id"),
                resultSet.getString("aggregate_id"), resultSet.getString("payload_json"), claimToken),
                Timestamp.from(now), claimLimit);
        claimInboundOutboxEvents(events.stream().map(OneBotAcceptanceEvent::id).toList(), claimToken, now,
                boundedClaimLease);
        return List.copyOf(events);
    }

    private int boundedInboundOutboxClaimLimit(int limit) {
        return Math.min(Math.max(limit, 0), MAXIMUM_INBOUND_OUTBOX_CLAIM_BATCH_SIZE);
    }

    private Duration boundedInboundOutboxClaimLease(Duration claimLease) {
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
        return claimLease.compareTo(MAXIMUM_INBOUND_OUTBOX_CLAIM_LEASE) > 0
                ? MAXIMUM_INBOUND_OUTBOX_CLAIM_LEASE : claimLease;
    }

    private void claimInboundOutboxEvents(List<UUID> eventIds, String claimToken, Instant now,
                                          Duration claimLease) {
        Timestamp claimUntil = Timestamp.from(now.plus(claimLease));
        for (UUID eventId : eventIds) {
            // 短事务持有的行锁保证一个事件只会绑定一个当前领取令牌。
            int updated = jdbcTemplate.update("""
                    UPDATE messaging_outbox SET claim_token = ?, claim_until = ?
                    WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                      AND (claim_until IS NULL OR claim_until <= ?)
                    """, claimToken, claimUntil, eventId.toString(), Timestamp.from(now));
            if (updated != 1) {
                throw new IllegalStateException("inbound outbox claim was lost");
            }
        }
    }

    private OneBotAcceptanceEvent mapOneBotAcceptanceEvent(String id, String aggregateId, String payloadJson,
                                                            String claimToken) {
        UUID eventId = UUID.fromString(id);
        UUID messageId = UUID.fromString(aggregateId);
        try {
            var payload = objectMapper.readTree(payloadJson);
            if (payload != null && payload.isTextual()) {
                // H2 的 MySQL 兼容 JSON 列可能以字符串节点返回，生产 MySQL 则直接返回对象节点。
                payload = objectMapper.readTree(payload.asText());
            }
            if (payload == null || !payload.isObject()) {
                return new OneBotAcceptanceEvent(eventId, messageId, "", "", false, claimToken);
            }
            String payloadMessageId = payload.path("messageId").asText("");
            String idempotencyKey = payload.path("idempotencyKey").asText("");
            String body = payload.path("body").asText("");
            boolean valid = messageId.toString().equals(payloadMessageId)
                    && !idempotencyKey.isBlank() && idempotencyKey.length() <= 255
                    && !body.isBlank() && body.length() <= 10_485_760;
            return new OneBotAcceptanceEvent(eventId, messageId, idempotencyKey, body, valid, claimToken);
        } catch (JsonProcessingException | RuntimeException exception) {
            // 坏载荷仍作为单个可识别事件返回，由中继一次性转入死信，不能阻断整个批次。
            return new OneBotAcceptanceEvent(eventId, messageId, "", "", false, claimToken);
        }
    }

    /**
     * 仅由仍持有领取令牌的 worker 标记 Outbox 事件已经获得下游确认。
     *
     * @param eventId 事件标识
     * @param claimToken 当前领取令牌
     * @return 是否提交成功
     */
    public boolean markOutboxPublished(UUID eventId, String claimToken) {
        return jdbcTemplate.update("""
                UPDATE messaging_outbox
                SET published_at = ?, next_attempt_at = NULL, last_error_code = NULL,
                    claim_token = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claim_token = ?
                """,
                Timestamp.from(Instant.now()), eventId.toString(), claimToken) == 1;
    }

    /**
     * 记录当前领取者的一次中继失败，并按有界指数退避安排下一次尝试。
     *
     * @param eventId 事件标识
     * @param claimToken 当前领取令牌
     * @param maximumAttempts 最大尝试次数
     * @param errorCode 稳定错误类别
     * @return 本次失败提交结果
     */
    @Transactional
    public InboundOutboxFailureOutcome recordInboundOutboxFailure(UUID eventId, String claimToken,
                                                                   int maximumAttempts, String errorCode) {
        int boundedMaximumAttempts = Math.max(1, maximumAttempts);
        List<Integer> currentAttempts = jdbcTemplate.query(
                """
                SELECT delivery_attempts FROM messaging_outbox
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claim_token = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getInt("delivery_attempts"), eventId.toString(), claimToken);
        if (currentAttempts.isEmpty()) {
            return InboundOutboxFailureOutcome.LOST_CLAIM;
        }
        int current = currentAttempts.getFirst();
        int attempts = current + 1;
        boolean exhausted = attempts >= boundedMaximumAttempts;
        long delaySeconds = Math.min(60L, 1L << Math.min(current, 6));
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE messaging_outbox
                SET delivery_attempts = ?, last_error_code = ?,
                    next_attempt_at = ?, dead_at = ?, claim_token = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claim_token = ? AND delivery_attempts = ?
                """, attempts, safeErrorCode(errorCode),
                exhausted ? null : Timestamp.from(now.plusSeconds(delaySeconds)),
                exhausted ? Timestamp.from(now) : null, eventId.toString(), claimToken, current);
        if (updated != 1) {
            // 新领取者已经接管时，迟到 worker 不得再次消耗失败预算。
            return InboundOutboxFailureOutcome.LOST_CLAIM;
        }
        return exhausted ? InboundOutboxFailureOutcome.DEAD
                : InboundOutboxFailureOutcome.RETRY_SCHEDULED;
    }

    /**
     * 统计已经进入人工处置状态的入站终态事件。
     *
     * @return 死信数量
     */
    public int countDeadMessageReceivedEvents() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE event_type = 'MessageReceived' AND published_at IS NULL AND dead_at IS NOT NULL
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 统计已经进入人工处置状态的 OneBot 受理回复事件。
     *
     * @return 死信数量
     */
    public int countDeadOneBotAcceptanceEvents() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE event_type = 'OneBotForwardAccepted' AND published_at IS NULL AND dead_at IS NOT NULL
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 将一个指定的入站死信恢复为待投递状态，保留原事件标识和载荷。
     *
     * @param eventId 事件标识
     * @return 当前事件状态；不是入站事件时为空
     */
    @Transactional
    public Optional<InboundOutboxRedrive> redriveDeadInboundEvent(UUID eventId) {
        int updated = jdbcTemplate.update("""
                UPDATE messaging_outbox
                SET delivery_attempts = 0, next_attempt_at = ?, dead_at = NULL,
                    claim_token = NULL, claim_until = NULL
                WHERE id = ? AND event_type IN ('MessageReceived', 'OneBotForwardAccepted')
                  AND published_at IS NULL AND dead_at IS NOT NULL
                """, Timestamp.from(Instant.now()), eventId.toString());
        return jdbcTemplate.query("""
                SELECT event_type, published_at, dead_at FROM messaging_outbox
                WHERE id = ? AND event_type IN ('MessageReceived', 'OneBotForwardAccepted')
                """, (resultSet, rowNumber) -> {
                    String status;
                    if (resultSet.getTimestamp("published_at") != null) {
                        status = "PUBLISHED";
                    } else if (resultSet.getTimestamp("dead_at") != null) {
                        status = "DEAD";
                    } else {
                        status = "PENDING";
                    }
                    return new InboundOutboxRedrive(eventId, resultSet.getString("event_type"), status,
                            updated == 1);
                }, eventId.toString()).stream().findFirst();
    }

    private String safeErrorCode(String errorCode) {
        String normalized = errorCode == null || errorCode.isBlank() ? "UnknownError" : errorCode.strip();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    /**
     * 等待转发的最小 Outbox 事件。
     */
    public record OutboxEvent(UUID id, UUID messageId, String claimToken) {
    }

    /**
     * 一个包含持久化回复载荷的 OneBot 受理事件。
     *
     * @param id 事件标识
     * @param messageId 入站消息标识
     * @param idempotencyKey 渠道回复幂等键
     * @param body 回复正文
     * @param valid 载荷是否完整且与聚合标识一致
     * @param claimToken 当前领取令牌
     */
    public record OneBotAcceptanceEvent(UUID id, UUID messageId, String idempotencyKey, String body,
                                        boolean valid, String claimToken) {
    }

    /**
     * 入站 Outbox 失败提交结果。
     */
    public enum InboundOutboxFailureOutcome {
        LOST_CLAIM,
        RETRY_SCHEDULED,
        DEAD
    }

    /**
     * 一个受控重驱后的入站 Outbox 状态。
     *
     * @param eventId 事件标识
     * @param eventType 事件类型
     * @param status 当前状态
     * @param redriven 本次调用是否执行了状态恢复
     */
    public record InboundOutboxRedrive(UUID eventId, String eventType, String status, boolean redriven) {
    }

    private Optional<InboundMessageView> findInbound(long ownerId, ChannelType type, String externalId) {
        return jdbcTemplate.query("""
                SELECT * FROM inbound_message WHERE owner_id = ? AND channel_type = ? AND external_message_id = ?
                """, (resultSet, rowNumber) -> mapInbound(resultSet), ownerId, type.name(), externalId)
                .stream().findFirst();
    }

    private InboundMessageView mapInbound(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        UUID messageId = UUID.fromString(resultSet.getString("id"));
        return new InboundMessageView(messageId, resultSet.getLong("owner_id"),
                ChannelType.valueOf(resultSet.getString("channel_type")),
                resultSet.getString("external_message_id"), resultSet.getString("conversation_key"),
                resultSet.getString("sender_ref"), resultSet.getString("subject_text"),
                resultSet.getString("body_text"), resultSet.getTimestamp("received_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                findInboundParts(messageId), isPreAcknowledged(messageId));
    }

    private boolean isPreAcknowledged(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE aggregate_id = ? AND event_type = 'OneBotForwardAccepted'
                  AND published_at IS NOT NULL
                """, Integer.class, messageId.toString());
        return count != null && count > 0;
    }

    private void insertInboundParts(UUID messageId, List<CreateInboundMessagePart> parts, Instant now) {
        insertInboundParts(messageId, parts, now, 0);
    }

    private void insertInboundParts(UUID messageId, List<CreateInboundMessagePart> parts, Instant now,
                                    int initialSequence) {
        List<Object[]> batchArguments = new ArrayList<>(parts.size());
        int sequence = initialSequence;
        for (CreateInboundMessagePart part : parts) {
            sequence++;
            batchArguments.add(new Object[]{UUID.randomUUID().toString(), messageId.toString(), sequence,
                    part.type(), part.text(),
                    part.attachmentType(), part.providerFileId(), part.providerAccountKey(), part.sourceUrl(),
                    part.fileName(), part.mimeType(), part.declaredSize(), Timestamp.from(now)});
        }
        if (!batchArguments.isEmpty()) {
            // 大型合并转发必须单批写入，避免每个附件产生一次数据库往返。
            jdbcTemplate.batchUpdate("""
                    INSERT INTO inbound_message_part
                        (id, inbound_message_id, sequence_number, part_type, text_content, attachment_type,
                         provider_file_id, provider_account_key, source_url, file_name, mime_type,
                         declared_size, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batchArguments);
        }
    }

    private List<InboundMessagePart> findInboundParts(UUID messageId) {
        return jdbcTemplate.query("""
                SELECT * FROM inbound_message_part WHERE inbound_message_id = ? ORDER BY sequence_number
                """, (resultSet, rowNumber) -> {
            long size = resultSet.getLong("declared_size");
            boolean sizeMissing = resultSet.wasNull();
            return new InboundMessagePart(UUID.fromString(resultSet.getString("id")),
                    resultSet.getInt("sequence_number"), resultSet.getString("part_type"),
                    resultSet.getString("text_content"), resultSet.getString("attachment_type"),
                    resultSet.getString("provider_file_id"), resultSet.getString("provider_account_key"),
                    resultSet.getString("source_url"),
                    resultSet.getString("file_name"), resultSet.getString("mime_type"),
                    sizeMissing ? null : size);
        }, messageId.toString());
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
