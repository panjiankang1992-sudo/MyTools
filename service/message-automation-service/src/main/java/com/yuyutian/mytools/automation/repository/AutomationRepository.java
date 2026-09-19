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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
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

    private static final int MAXIMUM_OUTBOX_ERROR_LENGTH = 128;
    private static final int MAXIMUM_RECONCILIATION_ERROR_LENGTH = 64;
    private static final long MAXIMUM_OUTBOX_RETRY_DELAY_SECONDS = 60L;
    private static final long MAXIMUM_RECONCILIATION_RETRY_DELAY_SECONDS = 60L;
    private static final long COMPLETION_CLAIM_LEASE_SECONDS = 300L;
    private static final int MAXIMUM_COMPLETION_CLAIM_BATCH_SIZE = 4;
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
     * @return 带创建权标识的权威运行
     */
    public RunStart beginRun(UUID messageId, AutomationRuleRecord rule) {
        Optional<AutomationRunView> existing = findRun(messageId);
        if (existing.isPresent()) {
            return new RunStart(existing.get(), false);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO automation_run
                        (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                         action_refs_json, error_code, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'RUNNING', 0, '[]', NULL, ?, ?)
                    """, id.toString(), messageId.toString(), rule == null ? null : rule.id().toString(),
                    rule == null ? null : rule.version(), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            // 唯一键冲突表示另一事务已取得创建权，当前事务只返回其权威快照。
            return new RunStart(findRun(messageId).orElseThrow(), false);
        }
        return new RunStart(findRun(messageId).orElseThrow(), true);
    }

    /**
     * 原子登记一个规范化消息链接。
     */
    public LinkClaim claimLink(long ownerId, UUID messageId, String normalizedUrl, String digest,
                               Instant processedAt) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO processed_message_link
                        (id, owner_id, url_sha256, normalized_url, inbound_message_id, status,
                         processed_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                    """, UUID.randomUUID().toString(), ownerId, digest, normalizedUrl,
                    messageId.toString(), Timestamp.from(processedAt), Timestamp.from(now));
            return new LinkClaim(true, normalizedUrl, processedAt, "PROCESSING");
        } catch (DuplicateKeyException exception) {
            LinkClaim existing = findLink(ownerId, digest).orElseThrow();
            if ("FAILED".equals(existing.status())) {
                int updated = jdbcTemplate.update("""
                        UPDATE processed_message_link
                        SET normalized_url = ?, inbound_message_id = ?, status = 'PROCESSING',
                            processed_at = ?, updated_at = ?
                        WHERE owner_id = ? AND url_sha256 = ? AND status = 'FAILED'
                        """, normalizedUrl, messageId.toString(), Timestamp.from(processedAt), Timestamp.from(now),
                        ownerId, digest);
                if (updated == 1) {
                    return new LinkClaim(true, normalizedUrl, processedAt, "PROCESSING");
                }
                existing = findLink(ownerId, digest).orElseThrow();
            }
            return existing;
        }
    }

    /**
     * 按运行终态更新本消息认领的链接。
     */
    public void completeLinks(UUID messageId, String runStatus) {
        String status = "SUCCEEDED".equals(runStatus) ? "SUCCEEDED"
                : List.of("FAILED", "PARTIAL_FAILED", "CANCELLED").contains(runStatus) ? "FAILED" : null;
        if (status != null) {
            jdbcTemplate.update("""
                    UPDATE processed_message_link SET status = ?, updated_at = ?
                    WHERE inbound_message_id = ? AND status = 'PROCESSING'
                    """, status, Timestamp.from(Instant.now()), messageId.toString());
        }
    }

    private Optional<LinkClaim> findLink(long ownerId, String digest) {
        return jdbcTemplate.query("""
                SELECT normalized_url, processed_at, status FROM processed_message_link
                WHERE owner_id = ? AND url_sha256 = ?
                """, (resultSet, rowNumber) -> new LinkClaim(false, resultSet.getString("normalized_url"),
                resultSet.getTimestamp("processed_at").toInstant(), resultSet.getString("status")),
                ownerId, digest).stream().findFirst();
    }

    /**
     * 原子完成运行并追加最小 Outbox 事件。
     */
    @Transactional
    public AutomationRunView completeRun(UUID messageId, String status, List<String> refs, String errorCode) {
        return completeRun(messageId, status, refs, errorCode, true);
    }

    /**
     * 原子完成运行，并按业务语义决定是否生成用户完成通知。
     *
     * @param messageId 消息标识
     * @param status 运行终态
     * @param refs 外部动作引用
     * @param errorCode 安全错误码
     * @param notify 是否生成完成通知事件
     * @return 最新运行快照
     */
    @Transactional
    public AutomationRunView completeRun(UUID messageId, String status, List<String> refs,
                                         String errorCode, boolean notify) {
        AutomationRunView current = findRun(messageId).orElseThrow();
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run SET status = ?, action_count = ?, action_refs_json = ?,
                    error_code = ?, updated_at = ?
                WHERE inbound_message_id = ? AND status = 'RUNNING'
                """, status, refs.size(), writeJson(refs), errorCode, Timestamp.from(now), messageId.toString());
        if (updated == 1 && notify) {
            // 只有真正完成 RUNNING 到终态推进的事务才能生成完成事件。
            appendOutbox(current.id(), "AutomationRunCompleted", Map.of(
                    "runId", current.id().toString(), "messageId", messageId.toString(), "status", status,
                    "actionCount", refs.size()));
        }
        if (updated == 1) {
            return findRun(messageId).orElseThrow();
        }
        // 条件更新失败表示其他事务已完成运行，锁定读取以绕过可重复读旧快照。
        return findRunForUpdate(messageId).orElseThrow();
    }

    /**
     * 原子完成仅包含重复链接的运行，并持久化专用反馈事件。
     *
     * @param messageId 消息标识
     * @return 运行快照和本次新建的反馈事件标识
     */
    @Transactional
    public DuplicateCompletion completeDuplicateOnlyRun(UUID messageId) {
        AutomationRunView current = findRun(messageId).orElseThrow();
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run SET status = 'SUCCEEDED', action_count = 0,
                    action_refs_json = '[]', error_code = NULL, updated_at = ?
                WHERE inbound_message_id = ? AND status = 'RUNNING'
                """, Timestamp.from(now), messageId.toString());
        UUID eventId = null;
        if (updated == 1) {
            // 专用事件不携带原始链接，避免反馈队列持久化敏感地址。
            eventId = appendOutbox(current.id(), "AutomationDuplicateLinksDetected", Map.of(
                    "runId", current.id().toString(), "messageId", messageId.toString(),
                    "status", "SUCCEEDED", "actionCount", 0));
        }
        AutomationRunView completed = updated == 1
                ? findRun(messageId).orElseThrow()
                : findRunForUpdate(messageId).orElseThrow();
        return new DuplicateCompletion(completed, Optional.ofNullable(eventId));
    }

    /**
     * 在旧版本运行被再次投递时补建缺失的重复链接反馈事件。
     *
     * @param messageId 消息标识
     * @return 权威运行快照和本次补建的事件标识
     */
    @Transactional
    public DuplicateCompletion ensureLegacyDuplicateOnlyFeedback(UUID messageId) {
        AutomationRunView run = findRunForUpdate(messageId).orElseThrow();
        boolean legacyDuplicateOnly = run.ruleId() != null
                && "SUCCEEDED".equals(run.status())
                && run.actionCount() == 0
                && run.errorCode() == null
                && run.actions().isEmpty();
        if (!legacyDuplicateOnly) {
            return new DuplicateCompletion(run, Optional.empty());
        }
        Integer existingEvents = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ?
                  AND event_type IN ('AutomationRunCompleted', 'AutomationDuplicateLinksDetected')
                """, Integer.class, run.id().toString());
        if (existingEvents != null && existingEvents > 0) {
            return new DuplicateCompletion(run, Optional.empty());
        }
        // 仅在实际重投时自愈，避免部署后向所有历史会话批量补发提示。
        UUID eventId = appendOutbox(run.id(), "AutomationDuplicateLinksDetected", Map.of(
                "runId", run.id().toString(), "messageId", messageId.toString(),
                "status", "SUCCEEDED", "actionCount", 0));
        return new DuplicateCompletion(run, Optional.of(eventId));
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
     * 仅由持有运行租约的 worker 绑定子动作外部请求。
     *
     * @param claim 运行租约
     * @param actionId 子动作标识
     * @param externalRequestId 外部请求标识
     * @param statusPollDelay 正常状态轮询间隔
     * @return 是否仍持有租约且完成了绑定
     */
    public boolean bindAction(RunClaim claim, UUID actionId, UUID externalRequestId,
                              Duration statusPollDelay) {
        Instant now = Instant.now();
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET external_request_id = ?, status = 'RUNNING', error_code = NULL,
                    poll_failure_attempts = 0, next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND automation_run_id = ? AND status = 'CREATING'
                  AND external_request_id IS NULL
                  AND EXISTS (
                      SELECT 1 FROM automation_run ar
                      WHERE ar.id = ? AND ar.status = 'RUNNING'
                        AND ar.reconcile_claimed_by = ? AND ar.reconcile_claim_until > ?)
                """, externalRequestId.toString(),
                nextActionPollAt(now, statusPollDelay), Timestamp.from(now),
                actionId.toString(), claim.runId().toString(), claim.runId().toString(), claim.claimToken(),
                Timestamp.from(now)) == 1;
    }

    /**
     * 记录租约持有者的子动作创建失败并保留幂等恢复能力。
     *
     * @param claim 运行租约
     * @param actionId 子动作标识
     * @param errorCode 稳定错误码
     * @param maximumAttempts 最大提交次数
     * @return 是否提交了本次状态推进
     */
    @Transactional
    public boolean failAction(RunClaim claim, UUID actionId, String errorCode, int maximumAttempts) {
        Instant ownershipCheckedAt = Instant.now();
        List<Integer> attemptRows = jdbcTemplate.query("""
                SELECT aa.submission_attempts FROM automation_action aa
                JOIN automation_run ar ON ar.id = aa.automation_run_id
                WHERE aa.id = ? AND aa.automation_run_id = ? AND aa.status = 'CREATING'
                  AND ar.status = 'RUNNING' AND ar.reconcile_claimed_by = ?
                  AND ar.reconcile_claim_until > ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getInt(1), actionId.toString(),
                claim.runId().toString(), claim.claimToken(), Timestamp.from(ownershipCheckedAt));
        if (attemptRows.isEmpty()) {
            return false;
        }
        int currentAttempts = attemptRows.getFirst();
        int nextAttempts = currentAttempts + 1;
        boolean exhausted = nextAttempts >= Math.max(1, maximumAttempts);
        Instant now = Instant.now();
        // 2、4、8、16、32 秒后进入每次 60 秒的封顶退避。
        long delaySeconds = Math.min(1L << Math.min(nextAttempts, 6), 60L);
        Timestamp nextAttemptAt = exhausted ? null : Timestamp.from(now.plusSeconds(delaySeconds));
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET submission_attempts = ?, status = ?, error_code = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND automation_run_id = ? AND status = 'CREATING'
                  AND submission_attempts = ?
                  AND EXISTS (
                      SELECT 1 FROM automation_run ar
                      WHERE ar.id = ? AND ar.status = 'RUNNING'
                        AND ar.reconcile_claimed_by = ? AND ar.reconcile_claim_until > ?)
                """, nextAttempts, exhausted ? "FAILED" : "CREATING", errorCode, nextAttemptAt,
                Timestamp.from(now), actionId.toString(), claim.runId().toString(), currentAttempts,
                claim.runId().toString(), claim.claimToken(), Timestamp.from(now)) == 1;
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
                """, (resultSet, rowNumber) -> mapActionExecution(resultSet), runId.toString());
    }

    /**
     * 查询本轮允许推进的创建动作。
     *
     * @param runId 运行标识
     * @param limit 最大返回数量
     * @return 到期的创建动作
     */
    public List<ActionExecution> findDueCreatingActions(UUID runId, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM automation_action
                WHERE automation_run_id = ?
                  AND status = 'CREATING'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY updated_at, sequence_number
                LIMIT ?
                """, (resultSet, rowNumber) -> mapActionExecution(resultSet), runId.toString(),
                Timestamp.from(Instant.now()), Math.max(0, limit));
    }

    /**
     * 查询本轮有界数量的需要轮询的已提交动作。
     *
     * @param runId 运行标识
     * @param limit 最大返回数量
     * @return 到期的已提交动作
     */
    public List<ActionExecution> findDueSubmittedActions(UUID runId, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM automation_action
                WHERE automation_run_id = ? AND external_request_id IS NOT NULL
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                ORDER BY updated_at, sequence_number
                LIMIT ?
                """, (resultSet, rowNumber) -> mapActionExecution(resultSet), runId.toString(),
                Timestamp.from(Instant.now()), Math.max(0, limit));
    }

    /**
     * 以单调条件更新子动作状态，供用户取消和计划初始化使用。
     *
     * @param actionId 子动作标识
     * @param status 新状态
     * @param errorCode 稳定错误码
     * @param statusPollDelay 正常状态轮询间隔
     * @return 是否完成状态推进
     */
    public boolean updateActionStatus(UUID actionId, String status, String errorCode,
                                      Duration statusPollDelay) {
        Instant now = Instant.now();
        Timestamp nextAttemptAt = "RUNNING".equals(status)
                ? nextActionPollAt(now, statusPollDelay) : null;
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET status = ?, error_code = ?, poll_failure_attempts = 0,
                    next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, status, errorCode, nextAttemptAt, Timestamp.from(now), actionId.toString()) == 1;
    }

    /**
     * 按调用方观察到的原状态单调更新子动作，避免迟到取消结果覆盖新状态。
     *
     * @param actionId 子动作标识
     * @param expectedStatus 调用前观察到的状态
     * @param status 新状态
     * @param errorCode 稳定错误码
     * @param statusPollDelay 正常状态轮询间隔
     * @return 是否完成状态推进
     */
    public boolean updateActionStatus(UUID actionId, String expectedStatus, String status,
                                      String errorCode, Duration statusPollDelay) {
        Instant now = Instant.now();
        Timestamp nextAttemptAt = "RUNNING".equals(status)
                ? nextActionPollAt(now, statusPollDelay) : null;
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET status = ?, error_code = ?, poll_failure_attempts = 0,
                    next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND status = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, status, errorCode, nextAttemptAt, Timestamp.from(now), actionId.toString(),
                expectedStatus) == 1;
    }

    /**
     * 仅由持有运行租约的 worker 单调更新子动作状态。
     *
     * @param claim 运行租约
     * @param actionId 子动作标识
     * @param expectedStatus 调用前观察到的状态
     * @param status 新状态
     * @param errorCode 稳定错误码
     * @param statusPollDelay 正常状态轮询间隔
     * @return 是否完成状态推进
     */
    public boolean updateActionStatus(RunClaim claim, UUID actionId, String expectedStatus,
                                      String status, String errorCode, Duration statusPollDelay) {
        Instant now = Instant.now();
        Timestamp nextAttemptAt = "RUNNING".equals(status)
                ? nextActionPollAt(now, statusPollDelay) : null;
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET status = ?, error_code = ?, poll_failure_attempts = 0,
                    next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND automation_run_id = ?
                  AND status = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND EXISTS (
                      SELECT 1 FROM automation_run ar
                      WHERE ar.id = ? AND ar.status = 'RUNNING'
                        AND ar.reconcile_claimed_by = ? AND ar.reconcile_claim_until > ?)
                """, status, errorCode, nextAttemptAt, Timestamp.from(now), actionId.toString(),
                claim.runId().toString(), expectedStatus, claim.runId().toString(), claim.claimToken(),
                Timestamp.from(now)) == 1;
    }

    private Timestamp nextActionPollAt(Instant now, Duration statusPollDelay) {
        if (statusPollDelay == null || statusPollDelay.isNegative() || statusPollDelay.isZero()) {
            throw new IllegalArgumentException("Action status poll delay must be positive");
        }
        return Timestamp.from(now.plus(statusPollDelay));
    }

    /**
     * 记录一次外部状态查询失败，并按独立预算退避或终止动作。
     *
     * @param claim 运行租约
     * @param actionId 动作标识
     * @param errorCode 稳定错误码
     * @param maximumAttempts 最大瞬时失败次数
     * @param permanent 是否为不可恢复错误
     * @return 是否提交了本次状态推进
     */
    @Transactional
    public boolean failActionPoll(RunClaim claim, UUID actionId, String errorCode,
                                  int maximumAttempts, boolean permanent) {
        Instant ownershipCheckedAt = Instant.now();
        List<Integer> attemptRows = jdbcTemplate.query("""
                SELECT aa.poll_failure_attempts FROM automation_action aa
                JOIN automation_run ar ON ar.id = aa.automation_run_id
                WHERE aa.id = ? AND aa.automation_run_id = ? AND aa.external_request_id IS NOT NULL
                  AND aa.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND ar.status = 'RUNNING' AND ar.reconcile_claimed_by = ?
                  AND ar.reconcile_claim_until > ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getInt(1), actionId.toString(),
                claim.runId().toString(), claim.claimToken(), Timestamp.from(ownershipCheckedAt));
        if (attemptRows.isEmpty()) {
            return false;
        }
        int currentAttempts = attemptRows.getFirst();
        int nextAttempts = currentAttempts + 1;
        boolean exhausted = permanent || nextAttempts >= maximumAttempts;
        Instant now = Instant.now();
        // 5、10、20、40 秒后进入 60 秒封顶退避，失败预算有界且不会影响成功轮询的长任务。
        long delaySeconds = Math.min(5L << Math.min(currentAttempts, 4), 60L);
        return jdbcTemplate.update("""
                UPDATE automation_action
                SET poll_failure_attempts = ?, status = ?, error_code = ?, next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND automation_run_id = ? AND external_request_id IS NOT NULL
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND poll_failure_attempts = ?
                  AND EXISTS (
                      SELECT 1 FROM automation_run ar
                      WHERE ar.id = ? AND ar.status = 'RUNNING'
                        AND ar.reconcile_claimed_by = ? AND ar.reconcile_claim_until > ?)
                """, nextAttempts, exhausted ? "FAILED" : "RUNNING", errorCode,
                exhausted ? null : Timestamp.from(now.plusSeconds(delaySeconds)), Timestamp.from(now),
                actionId.toString(), claim.runId().toString(), currentAttempts, claim.runId().toString(),
                claim.claimToken(), Timestamp.from(now)) == 1;
    }

    /**
     * 持久化当前租约已确认投递的下载进度。
     *
     * @param claim 运行租约
     * @param actionId 动作标识
     * @param percent 百分比
     * @return 是否仍持有租约且完成了进度推进
     */
    public boolean updateProgress(RunClaim claim, UUID actionId, int percent) {
        Instant now = Instant.now();
        return jdbcTemplate.update("""
                UPDATE automation_action SET last_progress_percent = GREATEST(last_progress_percent, ?),
                    updated_at = ?
                WHERE id = ? AND automation_run_id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                  AND EXISTS (
                      SELECT 1 FROM automation_run ar
                      WHERE ar.id = ? AND ar.status = 'RUNNING'
                        AND ar.reconcile_claimed_by = ? AND ar.reconcile_claim_until > ?)
                """, percent, Timestamp.from(now), actionId.toString(), claim.runId().toString(),
                claim.runId().toString(), claim.claimToken(), Timestamp.from(now)) == 1;
    }

    /**
     * 根据子动作重新计算运行聚合状态。
     */
    @Transactional
    public AutomationRunView updateRunAggregate(UUID messageId, String status, String errorCode) {
        AutomationRunView current = findRun(messageId).orElseThrow();
        List<AutomationActionView> actions = findActions(current.id());
        List<String> refs = actions.stream().map(AutomationActionView::externalRequestId)
                .filter(java.util.Objects::nonNull).map(UUID::toString).toList();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run SET status = ?, action_count = ?, action_refs_json = ?, error_code = ?,
                    updated_at = ? WHERE id = ? AND status = 'RUNNING'
                """, status, actions.size(), writeJson(refs), errorCode, Timestamp.from(Instant.now()),
                current.id().toString());
        if (updated == 1 && isTerminalRunStatus(status)) {
            // 条件更新的行数是完成事件的唯一生成凭据，避免并发对账重复写 Outbox。
            appendOutbox(current.id(), "AutomationRunCompleted", Map.of(
                    "runId", current.id().toString(), "messageId", messageId.toString(), "status", status,
                    "actionCount", actions.size()));
        }
        if (updated == 1) {
            return findRun(messageId).orElseThrow();
        }
        // 已终态的运行禁止被迟到的 RUNNING 或另一终态结果覆盖。
        return findRunForUpdate(messageId).orElseThrow();
    }

    /**
     * 仅由当前运行租约持有者重新计算并单调推进聚合状态。
     *
     * @param claim 运行租约
     * @param status 新聚合状态
     * @param errorCode 稳定错误码
     * @return 最新运行快照
     */
    @Transactional
    public AutomationRunView updateRunAggregate(RunClaim claim, String status, String errorCode) {
        AutomationRunView current = findRunById(claim.runId()).orElseThrow();
        List<AutomationActionView> actions = findActions(current.id());
        List<String> refs = actions.stream().map(AutomationActionView::externalRequestId)
                .filter(java.util.Objects::nonNull).map(UUID::toString).toList();
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run
                SET status = ?, action_count = ?, action_refs_json = ?, error_code = ?, updated_at = ?
                WHERE id = ? AND inbound_message_id = ? AND status = 'RUNNING'
                  AND reconcile_claimed_by = ? AND reconcile_claim_until > ?
                """, status, actions.size(), writeJson(refs), errorCode, Timestamp.from(now),
                claim.runId().toString(), claim.messageId().toString(), claim.claimToken(), Timestamp.from(now));
        if (updated == 1 && isTerminalRunStatus(status)) {
            // 只有当前租约成功推进 RUNNING 到终态时才能生成完成事件。
            appendOutbox(current.id(), "AutomationRunCompleted", Map.of(
                    "runId", current.id().toString(), "messageId", claim.messageId().toString(),
                    "status", status, "actionCount", actions.size()));
        }
        if (updated == 1) {
            return findRunById(claim.runId()).orElseThrow();
        }
        // 迟到 worker 只返回权威快照，不得覆盖已终态运行或新租约结果。
        return findRunForUpdate(claim.messageId()).orElseThrow();
    }

    private boolean isTerminalRunStatus(String status) {
        return List.of("SUCCEEDED", "FAILED", "PARTIAL_FAILED", "CANCELLED").contains(status);
    }

    private Optional<AutomationRunView> findRunForUpdate(UUID messageId) {
        return jdbcTemplate.query("SELECT * FROM automation_run WHERE inbound_message_id = ? FOR UPDATE",
                (resultSet, rowNumber) -> mapRun(resultSet), messageId.toString()).stream().findFirst();
    }

    /**
     * 查询等待邮件通知的终态事件。
     *
     * @param limit 最大返回数量
     * @return 按创建时间排序的终态事件
     */
    public List<CompletionEvent> findUnpublishedCompletions(int limit) {
        return jdbcTemplate.query("""
                SELECT ao.id AS event_id, ao.event_type, ao.delivery_page_cursor,
                       ar.id AS run_id, ar.inbound_message_id, ar.status, ar.action_count
                FROM automation_outbox ao
                JOIN automation_run ar ON ar.id = ao.aggregate_id
                WHERE ao.published_at IS NULL
                  AND ao.dead_at IS NULL
                  AND (ao.next_attempt_at IS NULL OR ao.next_attempt_at <= ?)
                  AND (ao.claim_until IS NULL OR ao.claim_until <= ?)
                  AND ao.event_type IN ('AutomationRunCompleted', 'AutomationDuplicateLinksDetected')
                ORDER BY ao.created_at, ao.id
                LIMIT ?
                """, (resultSet, rowNumber) -> new CompletionEvent(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getString("status"), resultSet.getInt("action_count"),
                resultSet.getInt("delivery_page_cursor"), null,
                resultSet.getString("event_type")),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), limit);
    }

    /**
     * 以租约抢占一批到期的完成事件，允许多个服务副本安全并行中继。
     *
     * @param limit 最大返回数量
     * @return 本次调用独占租约的完成事件
     */
    @Transactional
    public List<CompletionEvent> claimUnpublishedCompletions(int limit) {
        Instant now = Instant.now();
        String claimId = UUID.randomUUID().toString();
        int claimLimit = Math.min(Math.max(limit, 0), MAXIMUM_COMPLETION_CLAIM_BATCH_SIZE);
        if (claimLimit == 0) {
            return List.of();
        }
        List<CompletionEvent> events = jdbcTemplate.query("""
                SELECT ao.id AS event_id, ao.event_type, ao.delivery_page_cursor,
                       ar.id AS run_id, ar.inbound_message_id, ar.status, ar.action_count
                FROM automation_outbox ao
                JOIN automation_run ar ON ar.id = ao.aggregate_id
                WHERE ao.published_at IS NULL
                  AND ao.dead_at IS NULL
                  AND (ao.next_attempt_at IS NULL OR ao.next_attempt_at <= ?)
                  AND (ao.claim_until IS NULL OR ao.claim_until <= ?)
                  AND ao.event_type IN ('AutomationRunCompleted', 'AutomationDuplicateLinksDetected')
                ORDER BY ao.created_at, ao.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new CompletionEvent(
                UUID.fromString(resultSet.getString("event_id")),
                UUID.fromString(resultSet.getString("run_id")),
                UUID.fromString(resultSet.getString("inbound_message_id")),
                resultSet.getString("status"), resultSet.getInt("action_count"),
                resultSet.getInt("delivery_page_cursor"), claimId,
                resultSet.getString("event_type")),
                Timestamp.from(now), Timestamp.from(now), claimLimit);
        Timestamp claimUntil = Timestamp.from(now.plusSeconds(COMPLETION_CLAIM_LEASE_SECONDS));
        for (CompletionEvent event : events) {
            // 行锁保证同一事件只能被本次事务写入当前租约标识。
            int updated = jdbcTemplate.update("""
                    UPDATE automation_outbox SET claimed_by = ?, claim_until = ?
                    WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                    """, claimId, claimUntil, event.eventId().toString());
            if (updated != 1) {
                throw new IllegalStateException("completion outbox claim was lost");
            }
        }
        return List.copyOf(events);
    }

    /**
     * 以跳过锁的短事务抢占一批到期运行，供多实例并行对账。
     *
     * @param limit 最大抢占数量
     * @param lease 租约时长
     * @return 本次调用独占的运行租约
     */
    @Transactional
    public List<RunClaim> claimActiveRuns(int limit, Duration lease) {
        int claimLimit = Math.min(Math.max(limit, 0), 64);
        if (claimLimit == 0) {
            return List.of();
        }
        Instant now = Instant.now();
        List<RunIdentity> identities = jdbcTemplate.query("""
                SELECT ar.id, ar.inbound_message_id
                FROM automation_run ar
                WHERE ar.status = 'RUNNING'
                  AND (ar.next_reconcile_at IS NULL OR ar.next_reconcile_at <= ?)
                  AND (ar.reconcile_claim_until IS NULL OR ar.reconcile_claim_until <= ?)
                  AND (
                      NOT EXISTS (
                          SELECT 1 FROM automation_action finished
                          WHERE finished.automation_run_id = ar.id
                            AND finished.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
                      OR EXISTS (
                          SELECT 1 FROM automation_action due_action
                          WHERE due_action.automation_run_id = ar.id
                            AND (due_action.next_attempt_at IS NULL OR due_action.next_attempt_at <= ?)
                            AND (due_action.status = 'CREATING'
                              OR (due_action.external_request_id IS NOT NULL
                                AND due_action.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))))
                  )
                ORDER BY ar.updated_at, ar.id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (resultSet, rowNumber) -> new RunIdentity(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("inbound_message_id"))),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), claimLimit);
        Duration safeLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofSeconds(5) : lease;
        Instant claimUntil = now.plus(safeLease);
        List<RunClaim> claims = new java.util.ArrayList<>(identities.size());
        for (RunIdentity identity : identities) {
            String claimToken = UUID.randomUUID().toString();
            int updated = jdbcTemplate.update("""
                    UPDATE automation_run
                    SET reconcile_claimed_by = ?, reconcile_claim_until = ?
                    WHERE id = ? AND status = 'RUNNING'
                      AND (next_reconcile_at IS NULL OR next_reconcile_at <= ?)
                      AND (reconcile_claim_until IS NULL OR reconcile_claim_until <= ?)
                    """, claimToken, Timestamp.from(claimUntil), identity.runId().toString(),
                    Timestamp.from(now), Timestamp.from(now));
            if (updated != 1) {
                throw new IllegalStateException("automation run claim was lost");
            }
            claims.add(new RunClaim(identity.runId(), identity.messageId(), claimToken, claimUntil));
        }
        return List.copyOf(claims);
    }

    /**
     * 抢占指定消息的到期运行，供受控恢复和测试使用。
     *
     * @param messageId 消息标识
     * @param lease 租约时长
     * @return 成功取得的运行租约
     */
    @Transactional
    public Optional<RunClaim> claimRun(UUID messageId, Duration lease) {
        Instant now = Instant.now();
        List<RunIdentity> identities = jdbcTemplate.query("""
                SELECT ar.id, ar.inbound_message_id
                FROM automation_run ar
                WHERE ar.inbound_message_id = ? AND ar.status = 'RUNNING'
                  AND (ar.next_reconcile_at IS NULL OR ar.next_reconcile_at <= ?)
                  AND (ar.reconcile_claim_until IS NULL OR ar.reconcile_claim_until <= ?)
                  AND (
                      NOT EXISTS (
                          SELECT 1 FROM automation_action finished
                          WHERE finished.automation_run_id = ar.id
                            AND finished.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
                      OR EXISTS (
                          SELECT 1 FROM automation_action due_action
                          WHERE due_action.automation_run_id = ar.id
                            AND (due_action.next_attempt_at IS NULL OR due_action.next_attempt_at <= ?)
                            AND (due_action.status = 'CREATING'
                              OR (due_action.external_request_id IS NOT NULL
                                AND due_action.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))))
                  )
                FOR UPDATE
                """, (resultSet, rowNumber) -> new RunIdentity(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("inbound_message_id"))),
                messageId.toString(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (identities.isEmpty()) {
            return Optional.empty();
        }
        RunIdentity identity = identities.getFirst();
        Duration safeLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofSeconds(5) : lease;
        Instant claimUntil = now.plus(safeLease);
        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run
                SET reconcile_claimed_by = ?, reconcile_claim_until = ?
                WHERE id = ? AND status = 'RUNNING'
                  AND (next_reconcile_at IS NULL OR next_reconcile_at <= ?)
                  AND (reconcile_claim_until IS NULL OR reconcile_claim_until <= ?)
                """, claimToken, Timestamp.from(claimUntil), identity.runId().toString(),
                Timestamp.from(now), Timestamp.from(now));
        return updated == 1
                ? Optional.of(new RunClaim(identity.runId(), identity.messageId(), claimToken, claimUntil))
                : Optional.empty();
    }

    /**
     * 检查给定 worker 是否仍持有有效运行租约。
     *
     * @param claim 运行租约
     * @return 租约是否仍有效
     */
    public boolean ownsRunClaim(RunClaim claim) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_run
                WHERE id = ? AND inbound_message_id = ? AND status = 'RUNNING'
                  AND reconcile_claimed_by = ? AND reconcile_claim_until > ?
                """, Integer.class, claim.runId().toString(), claim.messageId().toString(),
                claim.claimToken(), Timestamp.from(Instant.now()));
        return count != null && count == 1;
    }

    /**
     * 成功完成一轮对账后释放当前租约并清除运行级失败退避。
     *
     * @param claim 运行租约
     * @return 是否释放了当前租约
     */
    public boolean releaseRunClaim(RunClaim claim) {
        return jdbcTemplate.update("""
                UPDATE automation_run
                SET reconcile_claimed_by = NULL, reconcile_claim_until = NULL,
                    reconciliation_failures = 0, next_reconcile_at = NULL,
                    last_reconciliation_error = NULL, updated_at = ?
                WHERE id = ? AND inbound_message_id = ? AND reconcile_claimed_by = ?
                """, Timestamp.from(Instant.now()), claim.runId().toString(), claim.messageId().toString(),
                claim.claimToken()) == 1;
    }

    /**
     * 记录整轮对账失败；瞬时失败退避，永久或耗尽预算时可靠终止运行。
     *
     * @param claim 运行租约
     * @param maximumAttempts 最大失败次数
     * @param permanent 是否不可恢复
     * @param errorCode 稳定错误码
     * @param errorType 安全错误类型
     * @return 本次失败处理结果
     */
    @Transactional
    public RunFailureOutcome recordRunReconciliationFailure(RunClaim claim, int maximumAttempts,
                                                             boolean permanent, String errorCode,
                                                             String errorType) {
        Instant ownershipCheckedAt = Instant.now();
        List<Integer> failureRows = jdbcTemplate.query("""
                SELECT reconciliation_failures FROM automation_run
                WHERE id = ? AND inbound_message_id = ? AND status = 'RUNNING'
                  AND reconcile_claimed_by = ? AND reconcile_claim_until > ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getInt(1), claim.runId().toString(),
                claim.messageId().toString(), claim.claimToken(), Timestamp.from(ownershipCheckedAt));
        if (failureRows.isEmpty()) {
            return RunFailureOutcome.LOST;
        }
        int nextFailures = failureRows.getFirst() + 1;
        Instant now = Instant.now();
        String safeErrorType = normalizeReconciliationError(errorType);
        if (!permanent && nextFailures < Math.max(1, maximumAttempts)) {
            long delaySeconds = Math.min(1L << Math.min(nextFailures, 6),
                    MAXIMUM_RECONCILIATION_RETRY_DELAY_SECONDS);
            int updated = jdbcTemplate.update("""
                    UPDATE automation_run
                    SET reconciliation_failures = ?, next_reconcile_at = ?,
                        last_reconciliation_error = ?, reconcile_claimed_by = NULL,
                        reconcile_claim_until = NULL, updated_at = ?
                    WHERE id = ? AND status = 'RUNNING' AND reconcile_claimed_by = ?
                    """, nextFailures, Timestamp.from(now.plusSeconds(delaySeconds)), safeErrorType,
                    Timestamp.from(now), claim.runId().toString(), claim.claimToken());
            return updated == 1 ? RunFailureOutcome.RETRY_SCHEDULED : RunFailureOutcome.LOST;
        }

        // 整轮预算耗尽时先终止全部非终态动作，再由同一事务生成唯一完成事件。
        jdbcTemplate.update("""
                UPDATE automation_action
                SET status = 'FAILED', error_code = ?, next_attempt_at = NULL, updated_at = ?
                WHERE automation_run_id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, errorCode, Timestamp.from(now), claim.runId().toString());
        List<AutomationActionView> actions = findActions(claim.runId());
        List<String> refs = actions.stream().map(AutomationActionView::externalRequestId)
                .filter(java.util.Objects::nonNull).map(UUID::toString).toList();
        int updated = jdbcTemplate.update("""
                UPDATE automation_run
                SET status = 'FAILED', action_count = ?, action_refs_json = ?, error_code = ?,
                    reconciliation_failures = ?, next_reconcile_at = NULL,
                    last_reconciliation_error = ?, reconcile_claimed_by = NULL,
                    reconcile_claim_until = NULL, updated_at = ?
                WHERE id = ? AND inbound_message_id = ? AND status = 'RUNNING'
                  AND reconcile_claimed_by = ? AND reconcile_claim_until > ?
                """, actions.size(), writeJson(refs), errorCode, nextFailures, safeErrorType,
                Timestamp.from(now), claim.runId().toString(), claim.messageId().toString(),
                claim.claimToken(), Timestamp.from(now));
        if (updated != 1) {
            return RunFailureOutcome.LOST;
        }
        appendOutbox(claim.runId(), "AutomationRunCompleted", Map.of(
                "runId", claim.runId().toString(), "messageId", claim.messageId().toString(),
                "status", "FAILED", "actionCount", actions.size()));
        completeLinks(claim.messageId(), "FAILED");
        return RunFailureOutcome.TERMINATED;
    }

    /**
     * 标记终态事件已由 Messaging 接收。
     *
     * @param eventId 事件标识
     */
    public boolean markOutboxPublished(UUID eventId) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET published_at = ?, next_attempt_at = NULL, last_error = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by IS NULL
                """, Timestamp.from(Instant.now()), eventId.toString()) == 1;
    }

    /**
     * 仅由仍持有租约的 worker 标记完成事件已投递。
     *
     * @param eventId 事件标识
     * @param claimToken 当前租约标识
     * @return 是否成功提交投递结果
     */
    public boolean markOutboxPublished(UUID eventId, String claimToken) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET published_at = ?, next_attempt_at = NULL, last_error = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by = ? AND claim_until > ?
                """, Timestamp.from(Instant.now()), eventId.toString(), claimToken,
                Timestamp.from(Instant.now())) == 1;
    }

    /**
     * 仅由仍持有租约的 worker 单调确认一页完成通知。
     *
     * @param eventId 事件标识
     * @param claimToken 当前租约标识
     * @param expectedCursor 发送前观察到的页游标
     * @return 是否仍持有租约且游标恰好推进一页
     */
    public boolean advanceOutboxDeliveryPage(UUID eventId, String claimToken, int expectedCursor) {
        if (expectedCursor < 0) {
            throw new IllegalArgumentException("Completion delivery page cursor must not be negative");
        }
        Instant now = Instant.now();
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_page_cursor = delivery_page_cursor + 1
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by = ? AND claim_until > ?
                  AND delivery_page_cursor = ?
                """, eventId.toString(), claimToken, Timestamp.from(now), expectedCursor) == 1;
    }

    /**
     * 在下游仍持有同一幂等投递租约时，无损延后当前完成事件。
     *
     * @param eventId 事件标识
     * @param claimToken 当前租约标识
     * @param retryAfter 下游建议的重试等待时间
     * @param errorType 安全的错误类型
     * @return 是否仍持有租约且成功延后事件
     */
    public boolean deferOutboxDelivery(UUID eventId, String claimToken,
                                       Duration retryAfter, String errorType) {
        if (claimToken == null || claimToken.isBlank()) {
            return false;
        }
        long requestedSeconds = retryAfter == null ? 1L : retryAfter.getSeconds();
        long delaySeconds = Math.max(1L,
                Math.min(requestedSeconds, MAXIMUM_OUTBOX_RETRY_DELAY_SECONDS));
        Instant now = Instant.now();
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET next_attempt_at = ?, last_error = ?, claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by = ? AND claim_until > ?
                """, Timestamp.from(now.plusSeconds(delaySeconds)), normalizeOutboxError(errorType),
                eventId.toString(), claimToken, Timestamp.from(now)) == 1;
    }

    /**
     * 将明确不可重试的终态事件标记为死信。
     *
     * @param eventId 事件标识
     * @param errorType 安全的错误类型
     */
    public boolean markOutboxDead(UUID eventId, String errorType) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_attempts = delivery_attempts + 1, dead_at = ?, next_attempt_at = NULL,
                    last_error = ?, claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by IS NULL
                """, Timestamp.from(Instant.now()), normalizeOutboxError(errorType),
                eventId.toString()) == 1;
    }

    /**
     * 仅由仍持有租约的 worker 将明确不可重试事件转为死信。
     *
     * @param eventId 事件标识
     * @param claimToken 当前租约标识
     * @param errorType 安全的错误类型
     * @return 是否成功提交死信结果
     */
    public boolean markOutboxDead(UUID eventId, String claimToken, String errorType) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_attempts = delivery_attempts + 1, dead_at = ?, next_attempt_at = NULL,
                    last_error = ?, claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                  AND claimed_by = ? AND claim_until > ?
                """, Timestamp.from(Instant.now()), normalizeOutboxError(errorType),
                eventId.toString(), claimToken, Timestamp.from(Instant.now())) == 1;
    }

    /**
     * 记录一次投递失败，未达上限时指数退避，达到上限时转为死信。
     *
     * @param eventId 事件标识
     * @param maximumAttempts 最大尝试次数
     * @param errorType 安全的错误类型
     * @return 是否已达到上限并转为死信
     */
    @Transactional
    public boolean recordOutboxFailure(UUID eventId, int maximumAttempts, String errorType) {
        return recordOutboxFailure(eventId, null, maximumAttempts, errorType);
    }

    /**
     * 记录仍由当前 worker 持有租约的投递失败。
     *
     * @param eventId 事件标识
     * @param claimToken 当前租约标识；空值仅允许操作未领取事件
     * @param maximumAttempts 最大尝试次数
     * @param errorType 安全的错误类型
     * @return 是否已达到上限并转为死信
     */
    @Transactional
    public boolean recordOutboxFailure(UUID eventId, String claimToken,
                                       int maximumAttempts, String errorType) {
        Instant ownershipCheckedAt = Instant.now();
        String ownershipCondition = claimToken == null
                ? " AND claimed_by IS NULL"
                : " AND claimed_by = ? AND claim_until > ?";
        Object[] ownershipArguments = claimToken == null
                ? new Object[]{eventId.toString()}
                : new Object[]{eventId.toString(), claimToken,
                    Timestamp.from(ownershipCheckedAt)};
        List<Integer> attemptRows = jdbcTemplate.query("""
                SELECT delivery_attempts FROM automation_outbox
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                """ + ownershipCondition + " FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getInt(1), ownershipArguments);
        if (attemptRows.isEmpty()) {
            return false;
        }
        int nextAttempt = attemptRows.getFirst() + 1;
        Instant now = Instant.now();
        String safeError = normalizeOutboxError(errorType);
        if (nextAttempt >= Math.max(1, maximumAttempts)) {
            // 达到上限后转死信，不写 published_at，保留真实投递结果。
            int updated = jdbcTemplate.update("""
                    UPDATE automation_outbox
                    SET delivery_attempts = ?, dead_at = ?, next_attempt_at = NULL, last_error = ?,
                        claimed_by = NULL, claim_until = NULL
                    WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                    """ + ownershipCondition, appendOwnershipArguments(
                    nextAttempt, Timestamp.from(now), safeError, ownershipArguments));
            return updated == 1;
        }
        long delaySeconds = Math.min(1L << Math.min(attemptRows.getFirst(), 6),
                MAXIMUM_OUTBOX_RETRY_DELAY_SECONDS);
        jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_attempts = ?, next_attempt_at = ?, last_error = ?,
                    claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND published_at IS NULL AND dead_at IS NULL
                """ + ownershipCondition, appendOwnershipArguments(
                nextAttempt, Timestamp.from(now.plusSeconds(delaySeconds)), safeError,
                ownershipArguments));
        return false;
    }

    private Object[] appendOwnershipArguments(Object first, Object second, Object third,
                                              Object[] ownershipArguments) {
        Object[] result = new Object[3 + ownershipArguments.length];
        result[0] = first;
        result[1] = second;
        result[2] = third;
        System.arraycopy(ownershipArguments, 0, result, 3, ownershipArguments.length);
        return result;
    }

    /**
     * 返回尚未投递且已进入死信的完成事件数量。
     *
     * @return 完成事件死信数量
     */
    public int countDeadCompletions() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE event_type IN ('AutomationRunCompleted', 'AutomationDuplicateLinksDetected')
                  AND published_at IS NULL AND dead_at IS NOT NULL
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 受控重置一条完成事件的投递预算，不删除事件或改变稳定标识。
     *
     * @param eventId 事件标识
     * @return 是否重置了一条完成事件死信
     */
    public boolean redriveDeadCompletion(UUID eventId) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_attempts = 0, next_attempt_at = NULL, last_error = NULL, dead_at = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE id = ?
                  AND event_type IN ('AutomationRunCompleted', 'AutomationDuplicateLinksDetected')
                  AND published_at IS NULL AND dead_at IS NOT NULL
                """, eventId.toString()) == 1;
    }

    /**
     * 仅恢复尚未发送分页且仍由旧终态不一致故障导致的下载完成死信。
     *
     * @param eventId 完成事件标识
     * @return 是否原子匹配并恢复了一条可恢复死信
     */
    public boolean redriveRecoverableDownloadCompletion(UUID eventId) {
        return jdbcTemplate.update("""
                UPDATE automation_outbox
                SET delivery_attempts = 0, next_attempt_at = NULL, last_error = NULL, dead_at = NULL,
                    claimed_by = NULL, claim_until = NULL
                WHERE id = ? AND event_type = 'AutomationRunCompleted'
                  AND published_at IS NULL AND dead_at IS NOT NULL
                  AND last_error = 'IllegalStateException'
                  AND delivery_page_cursor = 0
                """, eventId.toString()) == 1;
    }

    private String normalizeOutboxError(String errorType) {
        String normalized = errorType == null ? "" : errorType.strip();
        if (normalized.isEmpty() || !normalized.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            return "RuntimeException";
        }
        return normalized.length() <= MAXIMUM_OUTBOX_ERROR_LENGTH
                ? normalized : normalized.substring(0, MAXIMUM_OUTBOX_ERROR_LENGTH);
    }

    private String normalizeReconciliationError(String errorType) {
        String normalized = errorType == null ? "" : errorType.strip();
        if (normalized.isEmpty() || !normalized.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            return "RuntimeException";
        }
        return normalized.length() <= MAXIMUM_RECONCILIATION_ERROR_LENGTH
                ? normalized : normalized.substring(0, MAXIMUM_RECONCILIATION_ERROR_LENGTH);
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

    private ActionExecution mapActionExecution(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String externalId = resultSet.getString("external_request_id");
        return new ActionExecution(UUID.fromString(resultSet.getString("id")),
                resultSet.getInt("sequence_number"), resultSet.getString("action_type"),
                resultSet.getString("source_url"), resultSet.getString("file_name"),
                externalId == null ? null : UUID.fromString(externalId), resultSet.getString("status"),
                resultSet.getInt("last_progress_percent"));
    }

    private UUID appendOutbox(UUID aggregateId, String eventType, Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, payload_json, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """, eventId.toString(), aggregateId.toString(), eventType, writeJson(payload),
                Timestamp.from(Instant.now()));
        return eventId;
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
                                  UUID externalRequestId, String status, int lastProgressPercent) {
    }

    /**
     * 运行占位记录及本事务是否取得创建权。
     *
     * @param run 权威运行快照
     * @param created 是否由当前事务创建
     */
    public record RunStart(AutomationRunView run, boolean created) {
    }

    /**
     * 单轮运行对账的带期限所有权凭据。
     */
    public record RunClaim(UUID runId, UUID messageId, String claimToken, Instant claimUntil) {
    }

    /**
     * 运行级对账失败的持久化处理结果。
     */
    public enum RunFailureOutcome {
        LOST,
        RETRY_SCHEDULED,
        TERMINATED
    }

    private record RunIdentity(UUID runId, UUID messageId) {
    }

    /** 消息链接认领结果。 */
    public record LinkClaim(boolean claimed, String normalizedUrl, Instant processedAt, String status) {
    }

    /**
     * 仅重复链接运行的终态结果和可靠反馈事件标识。
     */
    public record DuplicateCompletion(AutomationRunView run, Optional<UUID> eventId) {
    }

    /**
     * 邮件完成通知所需的最小终态事件。
     */
    public record CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status,
                                  int actionCount, int deliveryPageCursor,
                                  String claimToken, String eventType) {

        /**
         * 拒绝数据库损坏或不受支持的负游标。
         */
        public CompletionEvent {
            if (deliveryPageCursor < 0) {
                throw new IllegalArgumentException(
                        "Completion delivery page cursor must not be negative");
            }
        }

        /**
         * 构造不携带租约的只读完成事件。
         */
        public CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status,
                               int actionCount) {
            this(eventId, runId, messageId, status, actionCount, 0, null,
                    "AutomationRunCompleted");
        }

        /**
         * 构造带租约的普通完成事件。
         */
        public CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status,
                               int actionCount, String claimToken) {
            this(eventId, runId, messageId, status, actionCount, 0, claimToken,
                    "AutomationRunCompleted");
        }

        /**
         * 构造带租约和持久页游标的普通完成事件。
         */
        public CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status,
                               int actionCount, int deliveryPageCursor, String claimToken) {
            this(eventId, runId, messageId, status, actionCount, deliveryPageCursor, claimToken,
                    "AutomationRunCompleted");
        }

        /**
         * 兼容构造带租约及事件类型但尚无页游标的完成事件。
         */
        public CompletionEvent(UUID eventId, UUID runId, UUID messageId, String status,
                               int actionCount, String claimToken, String eventType) {
            this(eventId, runId, messageId, status, actionCount, 0, claimToken, eventType);
        }

        /**
         * 判断事件是否为仅重复链接反馈。
         *
         * @return 是否应发送重复链接提示
         */
        public boolean duplicateOnly() {
            return "AutomationDuplicateLinksDetected".equals(eventType);
        }
    }
}
