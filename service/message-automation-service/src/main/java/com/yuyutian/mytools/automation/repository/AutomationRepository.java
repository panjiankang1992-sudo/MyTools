package com.yuyutian.mytools.automation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationActionView;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 自动化规则、运行和 Outbox 仓储。
 */
@Repository
public class AutomationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建自动化仓储。
     */
    public AutomationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等创建规则和唯一动作绑定。
     */
    public AutomationRuleRecord createRule(CreateAutomationRuleRequest request) {
        Optional<AutomationRuleRecord> existing = findRuleByName(request.ownerId(), request.name());
        if (existing.isPresent()) {
            if (!equivalent(existing.get(), request)) {
                throw new IllegalStateException("automation rule idempotency conflict");
            }
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_rule
                    (id, owner_id, name, channel_type, conversation_key, sender_ref, command_prefix,
                     priority, enabled, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id.toString(), request.ownerId(), request.name(), request.channelType().name(),
                blankToNull(request.conversationKey()), blankToNull(request.sender()), request.commandPrefix(),
                request.priority(), request.enabled(), Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO action_binding
                    (id, automation_rule_id, action_type, request_kind, max_actions, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                """, UUID.randomUUID().toString(), id.toString(),
                "MESSAGE_ATTACHMENT".equals(request.requestKind()) ? "DOWNLOAD_ATTACHMENT" : "DOWNLOAD_URL",
                request.requestKind(), request.maxActions(),
                Timestamp.from(now), Timestamp.from(now));
        return findRule(id).orElseThrow();
    }

    /**
     * 查询所有可能匹配的启用规则。
     */
    public List<AutomationRuleRecord> findEnabledRules(long ownerId, ChannelType channelType) {
        return queryRules("WHERE ar.owner_id = ? AND ar.channel_type = ? AND ar.enabled = TRUE "
                + "AND ab.enabled = TRUE ORDER BY ar.priority DESC, ar.id", ownerId, channelType.name());
    }

    /**
     * 按消息标识查询运行。
     */
    public Optional<AutomationRunView> findRun(UUID messageId) {
        return jdbcTemplate.query("SELECT * FROM automation_run WHERE inbound_message_id = ?",
                (resultSet, rowNumber) -> mapRun(resultSet), messageId.toString()).stream().findFirst();
    }

    /**
     * 按运行标识查询自动化运行。
     */
    public Optional<AutomationRunView> findRunById(UUID runId) {
        return jdbcTemplate.query("SELECT * FROM automation_run WHERE id = ?",
                (resultSet, rowNumber) -> mapRun(resultSet), runId.toString()).stream().findFirst();
    }

    /**
     * 抢占消息处理权并写入运行占位记录。
     *
     * @return 新运行，已存在时返回既有运行
     */
    public AutomationRunView beginRun(UUID messageId, AutomationRuleRecord rule) {
        Optional<AutomationRunView> existing = findRun(messageId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run
                    (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                     action_refs_json, error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'RUNNING', 0, '[]', NULL, ?, ?)
                """, id.toString(), messageId.toString(), rule == null ? null : rule.id().toString(),
                rule == null ? null : rule.version(), Timestamp.from(now), Timestamp.from(now));
        return findRun(messageId).orElseThrow();
    }

    /**
     * 原子完成运行并追加最小 Outbox 事件。
     */
    public AutomationRunView completeRun(UUID messageId, String status, List<String> refs, String errorCode) {
        AutomationRunView current = findRun(messageId).orElseThrow();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE automation_run SET status = ?, action_count = ?, action_refs_json = ?,
                    error_code = ?, updated_at = ? WHERE inbound_message_id = ?
                """, status, refs.size(), writeJson(refs), errorCode, Timestamp.from(now), messageId.toString());
        appendOutbox(current.id(), "AutomationRunCompleted", Map.of(
                "runId", current.id().toString(), "messageId", messageId.toString(), "status", status,
                "actionCount", refs.size()));
        return findRun(messageId).orElseThrow();
    }

    /**
     * 幂等创建一个子动作占位记录。
     */
    public AutomationActionView createAction(UUID runId, int sequence, String actionType,
                                             String sourceUrl, String fileName) {
        Optional<AutomationActionView> existing = findAction(runId, sequence);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_action
                    (id, automation_run_id, sequence_number, action_type, source_url, file_name,
                     external_request_id, status, error_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 'CREATING', NULL, ?, ?)
                """, id.toString(), runId.toString(), sequence, actionType, sourceUrl, fileName,
                Timestamp.from(now), Timestamp.from(now));
        return findAction(runId, sequence).orElseThrow();
    }

    /**
     * 绑定子动作的 Download Ingestion 请求。
     */
    public void bindAction(UUID actionId, UUID externalRequestId) {
        jdbcTemplate.update("""
                UPDATE automation_action SET external_request_id = ?, status = 'RUNNING', error_code = NULL,
                    updated_at = ? WHERE id = ? AND (external_request_id IS NULL OR external_request_id = ?)
                """, externalRequestId.toString(), Timestamp.from(Instant.now()), actionId.toString(),
                externalRequestId.toString());
    }

    /**
     * 记录子动作创建结果未知并保留幂等恢复能力。
     */
    public void failAction(UUID actionId, String errorCode) {
        jdbcTemplate.update("""
                UPDATE automation_action SET status = 'CREATING', error_code = ?, updated_at = ? WHERE id = ?
                """, errorCode, Timestamp.from(Instant.now()), actionId.toString());
    }

    /**
     * 查询运行的全部子动作。
     */
    public List<AutomationActionView> findActions(UUID runId) {
        return jdbcTemplate.query("""
                SELECT * FROM automation_action WHERE automation_run_id = ? ORDER BY sequence_number
                """, (resultSet, rowNumber) -> mapAction(resultSet), runId.toString());
    }

    /**
     * 查询包含私有输入的子动作执行快照。
     */
    public List<ActionExecution> findActionExecutions(UUID runId) {
        return jdbcTemplate.query("""
                SELECT * FROM automation_action WHERE automation_run_id = ? ORDER BY sequence_number
                """, (resultSet, rowNumber) -> {
            String externalId = resultSet.getString("external_request_id");
            return new ActionExecution(UUID.fromString(resultSet.getString("id")),
                    resultSet.getInt("sequence_number"), resultSet.getString("action_type"),
                    resultSet.getString("source_url"),
                    resultSet.getString("file_name"), externalId == null ? null : UUID.fromString(externalId),
                    resultSet.getString("status"));
        }, runId.toString());
    }

    /**
     * 更新子动作对账状态。
     */
    public void updateActionStatus(UUID actionId, String status, String errorCode) {
        jdbcTemplate.update("""
                UPDATE automation_action SET status = ?, error_code = ?, updated_at = ? WHERE id = ?
                """, status, errorCode, Timestamp.from(Instant.now()), actionId.toString());
    }

    /**
     * 根据子动作重新计算运行聚合状态。
     */
    public AutomationRunView updateRunAggregate(UUID messageId, String status, String errorCode) {
        AutomationRunView current = findRun(messageId).orElseThrow();
        List<AutomationActionView> actions = findActions(current.id());
        List<String> refs = actions.stream().map(AutomationActionView::externalRequestId)
                .filter(java.util.Objects::nonNull).map(UUID::toString).toList();
        jdbcTemplate.update("""
                UPDATE automation_run SET status = ?, action_count = ?, action_refs_json = ?, error_code = ?,
                    updated_at = ? WHERE id = ?
                """, status, actions.size(), writeJson(refs), errorCode, Timestamp.from(Instant.now()),
                current.id().toString());
        if (!current.status().equals(status) && List.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED", "CANCELLED")
                .contains(status)) {
            appendOutbox(current.id(), "AutomationRunCompleted", Map.of(
                    "runId", current.id().toString(), "messageId", messageId.toString(), "status", status,
                    "actionCount", actions.size()));
        }
        return findRun(messageId).orElseThrow();
    }

    /**
     * 查询等待邮件通知的终态事件。
     *
     * @param limit 最大返回数量
     * @return 按创建时间排序的终态事件
     */
    public List<CompletionEvent> findUnpublishedCompletions(ChannelType channelType, int limit) {
        return jdbcTemplate.query("""
                SELECT ao.id AS event_id, ar.id AS run_id, ar.inbound_message_id,
                       ar.status, ar.action_count
                FROM automation_outbox ao
                JOIN automation_run ar ON ar.id = ao.aggregate_id
                JOIN automation_rule rule ON rule.id = ar.automation_rule_id
                WHERE ao.published_at IS NULL
                  AND ao.event_type = 'AutomationRunCompleted'
                  AND rule.channel_type = ?
                ORDER BY ao.created_at, ao.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new CompletionEvent(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getString("status"), resultSet.getInt("action_count")), channelType.name(), limit);
    }

    /**
     * 查询需要后台对账的运行标识。
     */
    public List<UUID> findActiveMessageIds(int limit) {
        return jdbcTemplate.query("""
                SELECT inbound_message_id FROM automation_run
                WHERE status = 'RUNNING' ORDER BY updated_at, id LIMIT ?
                """, (resultSet, rowNumber) -> UUID.fromString(resultSet.getString(1)), limit);
    }

    /**
     * 标记终态事件已由 Messaging 接收。
     *
     * @param eventId 事件标识
     */
    public void markOutboxPublished(UUID eventId) {
        jdbcTemplate.update("UPDATE automation_outbox SET published_at = ? WHERE id = ? AND published_at IS NULL",
                Timestamp.from(Instant.now()), eventId.toString());
    }

    private Optional<AutomationRuleRecord> findRuleByName(long ownerId, String name) {
        return queryRules("WHERE ar.owner_id = ? AND ar.name = ?", ownerId, name).stream().findFirst();
    }

    private boolean equivalent(AutomationRuleRecord rule, CreateAutomationRuleRequest request) {
        return rule.ownerId() == request.ownerId()
                && rule.name().equals(request.name())
                && rule.channelType() == request.channelType()
                && java.util.Objects.equals(rule.conversationKey(), blankToNull(request.conversationKey()))
                && java.util.Objects.equals(rule.sender(), blankToNull(request.sender()))
                && rule.commandPrefix().equals(request.commandPrefix())
                && rule.requestKind().equals(request.requestKind())
                && rule.maxActions() == request.maxActions()
                && rule.priority() == request.priority()
                && rule.enabled() == request.enabled();
    }

    /**
     * 按标识查询自动化规则。
     */
    public Optional<AutomationRuleRecord> findRule(UUID id) {
        return queryRules("WHERE ar.id = ?", id.toString()).stream().findFirst();
    }

    private List<AutomationRuleRecord> queryRules(String clause, Object... arguments) {
        return jdbcTemplate.query("""
                SELECT ar.*, ab.request_kind, ab.max_actions FROM automation_rule ar
                JOIN action_binding ab ON ab.automation_rule_id = ar.id
                """ + clause, (resultSet, rowNumber) -> new AutomationRuleRecord(
                UUID.fromString(resultSet.getString("id")), resultSet.getLong("owner_id"),
                resultSet.getString("name"), ChannelType.valueOf(resultSet.getString("channel_type")),
                resultSet.getString("conversation_key"), resultSet.getString("sender_ref"),
                resultSet.getString("command_prefix"), resultSet.getInt("priority"),
                resultSet.getBoolean("enabled"), resultSet.getInt("version"),
                resultSet.getString("request_kind"), resultSet.getInt("max_actions"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant()),
                arguments);
    }

    private AutomationRunView mapRun(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String ruleId = resultSet.getString("automation_rule_id");
        Integer version = resultSet.getObject("rule_version", Integer.class);
        return new AutomationRunView(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                ruleId == null ? null : UUID.fromString(ruleId), version, resultSet.getString("status"),
                resultSet.getInt("action_count"), readList(resultSet.getString("action_refs_json")),
                resultSet.getString("error_code"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                findActions(UUID.fromString(resultSet.getString("id"))));
    }

    private Optional<AutomationActionView> findAction(UUID runId, int sequence) {
        return jdbcTemplate.query("""
                SELECT * FROM automation_action WHERE automation_run_id = ? AND sequence_number = ?
                """, (resultSet, rowNumber) -> mapAction(resultSet), runId.toString(), sequence)
                .stream().findFirst();
    }

    private AutomationActionView mapAction(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String externalId = resultSet.getString("external_request_id");
        return new AutomationActionView(UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("automation_run_id")),
                resultSet.getInt("sequence_number"), resultSet.getString("action_type"),
                externalId == null ? null : UUID.fromString(externalId), resultSet.getString("status"),
                resultSet.getString("error_code"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private void appendOutbox(UUID aggregateId, String eventType, Map<String, Object> payload) {
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, payload_json, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """, UUID.randomUUID().toString(), aggregateId.toString(), eventType, writeJson(payload),
                Timestamp.from(Instant.now()));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Automation value cannot be serialized", exception);
        }
    }

    private List<String> readList(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            return objectMapper.convertValue(node,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Stored automation refs are invalid", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 服务内部使用的子动作执行快照，不得直接作为 API 响应。
     */
    public record ActionExecution(UUID id, int sequence, String actionType, String sourceUrl, String fileName,
                                  UUID externalRequestId, String status) {
    }

    /**
     * 邮件完成通知所需的最小终态事件。
     */
    public record CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status, int actionCount) {
    }
}
