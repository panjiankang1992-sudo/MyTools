package com.yuyutian.mytools.automation.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动化运行终态条件推进测试。
 */
class AutomationRepositoryTerminalTransitionTest {

    @Test
    void shouldRecoverExpiredLeaseAndRejectEveryStaleTokenWrite() {
        Fixture fixture = fixture();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        insertRun(fixture.jdbcTemplate(), runId, messageId);
        insertCreatingAction(fixture.jdbcTemplate(), actionId, runId);

        AutomationRepository.RunClaim first = fixture.repository()
                .claimRun(messageId, Duration.ofSeconds(30)).orElseThrow();
        assertThat(fixture.repository().claimRun(messageId, Duration.ofSeconds(30))).isEmpty();
        fixture.jdbcTemplate().update("UPDATE automation_run SET reconcile_claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), runId.toString());
        AutomationRepository.RunClaim second = fixture.repository()
                .claimRun(messageId, Duration.ofSeconds(30)).orElseThrow();

        UUID externalRequestId = UUID.randomUUID();
        Duration pollDelay = Duration.ofMillis(250);
        assertThat(fixture.repository().bindAction(
                first, actionId, externalRequestId, pollDelay)).isFalse();
        Instant boundAt = Instant.now();
        assertThat(fixture.repository().bindAction(
                second, actionId, externalRequestId, pollDelay)).isTrue();
        Timestamp nextPollAt = fixture.jdbcTemplate().queryForObject(
                "SELECT next_attempt_at FROM automation_action WHERE id = ?", Timestamp.class,
                actionId.toString());
        assertThat(nextPollAt).isNotNull();
        assertThat(Duration.between(boundAt, nextPollAt.toInstant()).toMillis())
                .isBetween(150L, 1_000L);
        assertThat(fixture.repository().updateActionStatus(
                first, actionId, "RUNNING", "FAILED", "STALE", pollDelay)).isFalse();
        assertThat(fixture.repository().updateProgress(first, actionId, 50)).isFalse();
        assertThat(fixture.repository().updateActionStatus(
                second, actionId, "RUNNING", "SUCCEEDED", null, pollDelay)).isTrue();
        assertThat(fixture.repository().updateActionStatus(
                actionId, "RUNNING", null, pollDelay)).isFalse();

        var persisted = fixture.jdbcTemplate().queryForMap("""
                SELECT status, external_request_id, next_attempt_at
                FROM automation_action WHERE id = ?
                """, actionId.toString());
        assertThat(persisted.get("status")).isEqualTo("SUCCEEDED");
        assertThat(persisted.get("external_request_id")).isEqualTo(externalRequestId.toString());
        assertThat(persisted.get("next_attempt_at")).isNull();
        assertThat(fixture.repository().releaseRunClaim(first)).isFalse();
        assertThat(fixture.repository().releaseRunClaim(second)).isTrue();
    }

    @Test
    void shouldBackOffPoisonRunSoNewerDueRunCanBeClaimed() {
        Fixture fixture = fixture();
        UUID firstRunId = UUID.randomUUID();
        UUID firstMessageId = UUID.randomUUID();
        UUID secondRunId = UUID.randomUUID();
        UUID secondMessageId = UUID.randomUUID();
        insertRun(fixture.jdbcTemplate(), firstRunId, firstMessageId);
        insertCreatingAction(fixture.jdbcTemplate(), UUID.randomUUID(), firstRunId);
        insertRun(fixture.jdbcTemplate(), secondRunId, secondMessageId);
        insertCreatingAction(fixture.jdbcTemplate(), UUID.randomUUID(), secondRunId);
        fixture.jdbcTemplate().update("UPDATE automation_run SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH), firstRunId.toString());

        AutomationRepository.RunClaim poison = fixture.repository()
                .claimActiveRuns(1, Duration.ofSeconds(30)).getFirst();
        assertThat(poison.messageId()).isEqualTo(firstMessageId);
        assertThat(fixture.repository().recordRunReconciliationFailure(
                poison, 3, false, "AUTOMATION_006", "IllegalStateException"))
                .isEqualTo(AutomationRepository.RunFailureOutcome.RETRY_SCHEDULED);

        AutomationRepository.RunClaim next = fixture.repository()
                .claimActiveRuns(1, Duration.ofSeconds(30)).getFirst();
        assertThat(next.messageId()).isEqualTo(secondMessageId);
        assertThat(fixture.jdbcTemplate().queryForObject(
                "SELECT next_reconcile_at FROM automation_run WHERE id = ?", Timestamp.class,
                firstRunId.toString())).isAfter(Timestamp.from(Instant.now()));
        fixture.repository().releaseRunClaim(next);
    }

    @Test
    void shouldTerminatePoisonRunOnceAndCreateCompletionWhenBudgetIsExhausted() {
        Fixture fixture = fixture();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        insertRun(fixture.jdbcTemplate(), runId, messageId);
        insertCreatingAction(fixture.jdbcTemplate(), actionId, runId);
        AutomationRepository.RunClaim claim = fixture.repository()
                .claimRun(messageId, Duration.ofSeconds(30)).orElseThrow();

        assertThat(fixture.repository().recordRunReconciliationFailure(
                claim, 1, false, "AUTOMATION_006", "IllegalStateException"))
                .isEqualTo(AutomationRepository.RunFailureOutcome.TERMINATED);
        assertThat(fixture.repository().recordRunReconciliationFailure(
                claim, 1, false, "AUTOMATION_006", "IllegalStateException"))
                .isEqualTo(AutomationRepository.RunFailureOutcome.LOST);

        assertThat(fixture.repository().findRun(messageId).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(fixture.repository().findActions(runId)).extracting("status").containsExactly("FAILED");
        assertThat(completionCount(fixture.jdbcTemplate(), runId)).isEqualTo(1);
    }

    @Test
    void shouldEmitOneCompletionWhenCompleteRunCompetes() throws Exception {
        Fixture fixture = fixture();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        insertRun(fixture.jdbcTemplate(), runId, messageId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AutomationRunView> succeeded = executor.submit(() -> completeAfterStart(
                    fixture, ready, start, messageId, "SUCCEEDED", null));
            Future<AutomationRunView> failed = executor.submit(() -> completeAfterStart(
                    fixture, ready, start, messageId, "FAILED", "DOWNLOAD_CREATE_FAILED"));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            AutomationRunView firstResult = succeeded.get(10, TimeUnit.SECONDS);
            AutomationRunView secondResult = failed.get(10, TimeUnit.SECONDS);
            String persistedStatus = fixture.jdbcTemplate().queryForObject(
                    "SELECT status FROM automation_run WHERE id = ?", String.class, runId.toString());

            assertThat(persistedStatus).isIn("SUCCEEDED", "FAILED");
            assertThat(firstResult.status()).isEqualTo(persistedStatus);
            assertThat(secondResult.status()).isEqualTo(persistedStatus);
            assertThat(completionCount(fixture.jdbcTemplate(), runId)).isEqualTo(1);
        }
    }

    @Test
    void shouldNotDuplicateOrOverwriteAggregateCompletion() {
        Fixture fixture = fixture();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID externalRequestId = UUID.randomUUID();
        insertRun(fixture.jdbcTemplate(), runId, messageId);
        insertAction(fixture.jdbcTemplate(), actionId, runId, externalRequestId);

        AutomationRunView completed = fixture.transactionTemplate().execute(status ->
                fixture.repository().updateRunAggregate(messageId, "SUCCEEDED", null));
        AutomationRunView repeated = fixture.transactionTemplate().execute(status ->
                fixture.repository().updateRunAggregate(messageId, "FAILED", "DOWNLOAD_CREATE_FAILED"));

        assertThat(completed).isNotNull();
        assertThat(repeated).isNotNull();
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(repeated.status()).isEqualTo("SUCCEEDED");
        assertThat(repeated.actionRefs()).containsExactly(externalRequestId.toString());
        assertThat(completionCount(fixture.jdbcTemplate(), runId)).isEqualTo(1);
    }

    private AutomationRunView completeAfterStart(Fixture fixture, CountDownLatch ready, CountDownLatch start,
                                                  UUID messageId, String status, String errorCode)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent terminal transition did not start");
        }
        return fixture.transactionTemplate().execute(transactionStatus ->
                fixture.repository().completeRun(messageId, status, List.of(), errorCode));
    }

    private Fixture fixture() {
        String databaseName = "automation_terminal_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema(jdbcTemplate);
        return new Fixture(jdbcTemplate, new AutomationRepository(jdbcTemplate, new ObjectMapper()),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE automation_run (
                    id CHAR(36) PRIMARY KEY,
                    inbound_message_id CHAR(36) NOT NULL UNIQUE,
                    automation_rule_id CHAR(36),
                    rule_version INT,
                    status VARCHAR(32) NOT NULL,
                    action_count INT NOT NULL,
                    action_refs_json JSON NOT NULL,
                    error_code VARCHAR(64),
                    reconcile_claimed_by VARCHAR(64),
                    reconcile_claim_until TIMESTAMP(6),
                    reconciliation_failures INT NOT NULL DEFAULT 0,
                    next_reconcile_at TIMESTAMP(6),
                    last_reconciliation_error VARCHAR(64),
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE automation_action (
                    id CHAR(36) PRIMARY KEY,
                    automation_run_id CHAR(36) NOT NULL,
                    sequence_number INT NOT NULL,
                    action_type VARCHAR(64) NOT NULL,
                    source_url VARCHAR(4096) NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    external_request_id CHAR(36),
                    status VARCHAR(32) NOT NULL,
                    error_code VARCHAR(64),
                    last_progress_percent INT NOT NULL DEFAULT 0,
                    submission_attempts INT NOT NULL DEFAULT 0,
                    poll_failure_attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP(6),
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (automation_run_id, sequence_number)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE automation_outbox (
                    id CHAR(36) PRIMARY KEY,
                    aggregate_id CHAR(36) NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    payload_json JSON NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    published_at TIMESTAMP(6),
                    delivery_attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP(6),
                    last_error VARCHAR(128),
                    dead_at TIMESTAMP(6),
                    claimed_by VARCHAR(64),
                    claim_until TIMESTAMP(6),
                    delivery_page_cursor INT NOT NULL DEFAULT 0,
                    CONSTRAINT chk_automation_outbox_delivery_page_cursor
                        CHECK (delivery_page_cursor >= 0)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE processed_message_link (
                    id CHAR(36) PRIMARY KEY,
                    inbound_message_id CHAR(36) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
    }

    private void insertRun(JdbcTemplate jdbcTemplate, UUID runId, UUID messageId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run
                    (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                     action_refs_json, error_code, created_at, updated_at)
                VALUES (?, ?, NULL, NULL, 'RUNNING', 0, '[]', NULL, ?, ?)
                """, runId.toString(), messageId.toString(), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertAction(JdbcTemplate jdbcTemplate, UUID actionId, UUID runId, UUID externalRequestId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_action
                    (id, automation_run_id, sequence_number, action_type, source_url, file_name,
                     external_request_id, status, error_code, created_at, updated_at)
                VALUES (?, ?, 0, 'DOWNLOAD_REQUEST', 'https://files.example/item', 'item.bin',
                        ?, 'SUCCEEDED', NULL, ?, ?)
                """, actionId.toString(), runId.toString(), externalRequestId.toString(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private void insertCreatingAction(JdbcTemplate jdbcTemplate, UUID actionId, UUID runId) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_action
                    (id, automation_run_id, sequence_number, action_type, source_url, file_name,
                     external_request_id, status, error_code, created_at, updated_at)
                VALUES (?, ?, 0, 'DOWNLOAD_REQUEST', 'https://files.example/item', 'item.bin',
                        NULL, 'CREATING', NULL, ?, ?)
                """, actionId.toString(), runId.toString(), Timestamp.from(now), Timestamp.from(now));
    }

    private int completionCount(JdbcTemplate jdbcTemplate, UUID runId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ? AND event_type = 'AutomationRunCompleted'
                """, Integer.class, runId.toString());
        return count == null ? 0 : count;
    }

    private record Fixture(JdbcTemplate jdbcTemplate, AutomationRepository repository,
                           TransactionTemplate transactionTemplate) {
    }
}
