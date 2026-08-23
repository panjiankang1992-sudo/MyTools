package com.yuyutian.mytools.automation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
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
                VALUES (?, ?, 'DOWNLOAD_URL', ?, ?, TRUE, ?, ?)
                """, UUID.randomUUID().toString(), id.toString(), request.requestKind(), request.maxActions(),
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

    private Optional<AutomationRuleRecord> findRuleByName(long ownerId, String name) {
        return queryRules("WHERE ar.owner_id = ? AND ar.name = ?", ownerId, name).stream().findFirst();
    }

    private Optional<AutomationRuleRecord> findRule(UUID id) {
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
}
