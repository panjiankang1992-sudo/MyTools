package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动化完成通知中继测试。
 */
class CompletionOutboxRelayTest {

    @Test
    void shouldRelayDuplicateFeedbackWithRunScopedIdentityWithoutReadingMessage() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 0, "claim-1",
                "AutomationDuplicateLinksDetected");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.reply(messageId, "automation-duplicate-links-" + runId,
                DuplicateLinkFeedback.body())).thenReturn(
                new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient, never()).get(any());
        verify(messagingClient).reply(messageId, "automation-duplicate-links-" + runId,
                DuplicateLinkFeedback.body());
        verify(repository).markOutboxPublished(eventId, "claim-1");
    }

    @Test
    void shouldExplainPreAcknowledgedNoMatchAsTerminalCompletion() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "NO_MATCH", 0);
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.reply(messageId, runId,
                "未匹配到下载规则或消息中没有可处理内容，本次未创建下载任务。"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "未匹配到下载规则或消息中没有可处理内容，本次未创建下载任务。");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldConfirmOutboxOnlyAfterMessagingAcceptsDelivery() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 2);
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.reply(messageId, runId, "下载处理已完成，共 2 个文件。"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(messageId, "EMAIL", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldRetainOutboxWhenMessagingFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID goodEventId = UUID.randomUUID();
        UUID goodRunId = UUID.randomUUID();
        UUID goodMessageId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1),
                new AutomationRepository.CompletionEvent(goodEventId, goodRunId, goodMessageId,
                        "SUCCEEDED", 1)));
        when(messagingClient.get(messageId)).thenThrow(new IllegalStateException("temporary failure"));
        when(messagingClient.get(goodMessageId)).thenReturn(message(goodMessageId));
        when(messagingClient.reply(goodMessageId, goodRunId, "下载处理已完成，共 1 个文件。"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(goodMessageId, "EMAIL", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository, never()).markOutboxPublished(eventId, null);
        verify(repository).recordOutboxFailure(eventId, null, 10, "IllegalStateException");
        verify(repository).markOutboxPublished(goodEventId, null);
    }

    @Test
    void shouldDiscardPermanentFailureAndContinueBatch() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID badEventId = UUID.randomUUID();
        UUID badMessageId = UUID.randomUUID();
        UUID goodEventId = UUID.randomUUID();
        UUID goodRunId = UUID.randomUUID();
        UUID goodMessageId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(badEventId, UUID.randomUUID(), badMessageId,
                        "FAILED", 1),
                new AutomationRepository.CompletionEvent(goodEventId, goodRunId, goodMessageId,
                        "SUCCEEDED", 1)));
        when(messagingClient.get(badMessageId)).thenThrow(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        when(messagingClient.get(goodMessageId)).thenReturn(message(goodMessageId));
        when(messagingClient.reply(goodMessageId, goodRunId, "下载处理已完成，共 1 个文件。"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(goodMessageId, "EMAIL", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(repository, never()).markOutboxPublished(badEventId, null);
        verify(repository).markOutboxDead(badEventId, null, "HttpClientErrorException");
        verify(repository).markOutboxPublished(goodEventId, null);
    }

    @Test
    void shouldDeferTooEarlyReplyWithoutConsumingDeliveryAttempt() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 1, 0, "claim-425");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(qqMessage(messageId));
        when(messagingClient.reply(eq(messageId), any(String.class), any(String.class)))
                .thenThrow(clientError(HttpStatus.TOO_EARLY, "17"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).deferOutboxDelivery(
                eventId, "claim-425", Duration.ofSeconds(17), "HttpClientErrorException");
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq("claim-425"), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(repository, never()).markOutboxDead(
                eq(eventId), eq("claim-425"), org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).markOutboxPublished(eventId, "claim-425");
    }

    @Test
    void shouldBoundExcessiveTooEarlyRetryAfter() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 1, 0, "claim-bounded");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(qqMessage(messageId));
        when(messagingClient.reply(eq(messageId), any(String.class), any(String.class)))
                .thenThrow(clientError(HttpStatus.TOO_EARLY, "3600"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).deferOutboxDelivery(
                eventId, "claim-bounded", Duration.ofSeconds(60), "HttpClientErrorException");
    }

    @Test
    void shouldDeadLetterQqWalConflictAndExhaustionImmediately() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID conflictEventId = UUID.randomUUID();
        UUID conflictRunId = UUID.randomUUID();
        UUID conflictMessageId = UUID.randomUUID();
        UUID deadEventId = UUID.randomUUID();
        UUID deadRunId = UUID.randomUUID();
        UUID deadMessageId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        conflictEventId, conflictRunId, conflictMessageId,
                        "SUCCEEDED", 1, 0, "claim-409"),
                new AutomationRepository.CompletionEvent(
                        deadEventId, deadRunId, deadMessageId,
                        "SUCCEEDED", 1, 0, "claim-422")));
        when(messagingClient.get(conflictMessageId)).thenReturn(qqMessage(conflictMessageId));
        when(messagingClient.get(deadMessageId)).thenReturn(qqMessage(deadMessageId));
        when(messagingClient.reply(eq(conflictMessageId), any(String.class), any(String.class)))
                .thenThrow(clientError(HttpStatus.CONFLICT, null));
        when(messagingClient.reply(eq(deadMessageId), any(String.class), any(String.class)))
                .thenThrow(clientError(HttpStatus.UNPROCESSABLE_ENTITY, null));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).markOutboxDead(
                conflictEventId, "claim-409", "Conflict");
        verify(repository).markOutboxDead(
                deadEventId, "claim-422", "UnprocessableEntity");
        verify(repository, never()).recordOutboxFailure(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldBackOffAndEventuallyDeadLetterWithoutPretendingPublished() {
        JdbcTemplate jdbcTemplate = outboxJdbcTemplate();
        AutomationRepository repository = new AutomationRepository(jdbcTemplate, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run (id, inbound_message_id, status, action_count)
                VALUES (?, ?, 'SUCCEEDED', 1)
                """, runId.toString(), messageId.toString());
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, created_at, published_at, delivery_attempts,
                     next_attempt_at, last_error, dead_at)
                VALUES (?, ?, 'AutomationRunCompleted', ?, NULL, 0, NULL, NULL, NULL)
        """, eventId.toString(), runId.toString(), Timestamp.from(createdAt));

        Instant firstAttemptAt = Instant.now();
        assertThat(repository.recordOutboxFailure(
                eventId, 3, "IllegalStateException\nsensitive-value")).isFalse();
        Timestamp firstRetryAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM automation_outbox WHERE id = ?", Timestamp.class,
                eventId.toString());
        assertThat(firstRetryAt).isNotNull();
        assertThat(Duration.between(firstAttemptAt, firstRetryAt.toInstant()).toMillis())
                .isBetween(800L, 3_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM automation_outbox WHERE id = ?", String.class,
                eventId.toString())).isEqualTo("RuntimeException");
        assertThat(repository.findUnpublishedCompletions(10)).isEmpty();

        // 人工推进到期时间，验证第二次失败使退避翻倍。
        jdbcTemplate.update("UPDATE automation_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());
        assertThat(repository.findUnpublishedCompletions(10)).hasSize(1);
        Instant secondAttemptAt = Instant.now();
        assertThat(repository.recordOutboxFailure(eventId, 3, "X".repeat(200))).isFalse();
        Timestamp secondRetryAt = jdbcTemplate.queryForObject(
                "SELECT next_attempt_at FROM automation_outbox WHERE id = ?", Timestamp.class,
                eventId.toString());
        assertThat(secondRetryAt).isNotNull();
        assertThat(Duration.between(secondAttemptAt, secondRetryAt.toInstant()).toMillis())
                .isBetween(1_800L, 4_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM automation_outbox WHERE id = ?", String.class,
                eventId.toString())).hasSize(128).matches("[A-Za-z0-9_.-]+");

        assertThat(repository.recordOutboxFailure(eventId, 3, "IllegalStateException")).isTrue();
        var terminal = jdbcTemplate.queryForMap(
                "SELECT delivery_attempts, published_at, next_attempt_at, last_error, dead_at "
                        + "FROM automation_outbox WHERE id = ?", eventId.toString());
        assertThat(terminal.get("delivery_attempts")).isEqualTo(3);
        assertThat(terminal.get("published_at")).isNull();
        assertThat(terminal.get("next_attempt_at")).isNull();
        assertThat(terminal.get("last_error")).isEqualTo("IllegalStateException");
        assertThat(terminal.get("dead_at")).isNotNull();
        assertThat(repository.findUnpublishedCompletions(10)).isEmpty();
    }

    @Test
    void shouldLeaseCompletionOnceAndRedriveDeadEventWithSameIdentity() {
        JdbcTemplate jdbcTemplate = outboxJdbcTemplate();
        AutomationRepository repository = new AutomationRepository(jdbcTemplate, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_run (id, inbound_message_id, status, action_count)
                VALUES (?, ?, 'SUCCEEDED', 1)
                """, runId.toString(), messageId.toString());
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, created_at, published_at, delivery_attempts,
                     next_attempt_at, last_error, dead_at, claimed_by, claim_until)
                VALUES (?, ?, 'AutomationRunCompleted', ?, NULL, 0, NULL, NULL, NULL, NULL, NULL)
                """, eventId.toString(), runId.toString(), Timestamp.from(Instant.now()));

        AutomationRepository.CompletionEvent firstClaim =
                repository.claimUnpublishedCompletions(10).getFirst();
        assertThat(firstClaim.eventId()).isEqualTo(eventId);
        assertThat(firstClaim.claimToken()).isNotBlank();
        assertThat(firstClaim.deliveryPageCursor()).isZero();
        assertThat(repository.claimUnpublishedCompletions(10)).isEmpty();
        assertThat(repository.advanceOutboxDeliveryPage(
                eventId, firstClaim.claimToken(), 0)).isTrue();
        assertThat(repository.advanceOutboxDeliveryPage(
                eventId, firstClaim.claimToken(), 0)).isFalse();

        // 租约过期并被新 worker 领取后，旧 worker 的任何结果均不得覆盖新拥有者。
        jdbcTemplate.update("UPDATE automation_outbox SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());
        AutomationRepository.CompletionEvent secondClaim =
                repository.claimUnpublishedCompletions(10).getFirst();
        assertThat(secondClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(secondClaim.deliveryPageCursor()).isOne();
        assertThat(repository.advanceOutboxDeliveryPage(
                eventId, firstClaim.claimToken(), 1)).isFalse();
        assertThat(repository.advanceOutboxDeliveryPage(
                eventId, secondClaim.claimToken(), 1)).isTrue();
        assertThat(repository.markOutboxPublished(eventId, firstClaim.claimToken())).isFalse();
        assertThat(repository.recordOutboxFailure(
                eventId, firstClaim.claimToken(), 3, "IllegalStateException")).isFalse();
        assertThat(repository.markOutboxDead(
                eventId, firstClaim.claimToken(), "HttpClientErrorException")).isFalse();
        assertThat(repository.markOutboxDead(
                eventId, secondClaim.claimToken(), "HttpClientErrorException")).isTrue();
        assertThat(repository.countDeadCompletions()).isEqualTo(1);
        assertThat(repository.redriveDeadCompletion(eventId)).isTrue();
        assertThat(repository.countDeadCompletions()).isZero();
        assertThat(repository.claimUnpublishedCompletions(10))
                .satisfiesExactly(redriven -> {
                    assertThat(redriven.eventId()).isEqualTo(eventId);
                    assertThat(redriven.deliveryPageCursor()).isEqualTo(2);
                });
    }

    @Test
    void shouldConditionallyRedriveOnlyRecoverableUnsentDownloadCompletion() {
        JdbcTemplate jdbcTemplate = outboxJdbcTemplate();
        AutomationRepository repository = new AutomationRepository(jdbcTemplate, new ObjectMapper());
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID recoverableId = UUID.randomUUID();
        UUID advancedCursorId = UUID.randomUUID();
        UUID changedErrorId = UUID.randomUUID();
        UUID liveId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_run (id, inbound_message_id, status, action_count)
                VALUES (?, ?, 'FAILED', 4)
                """, runId.toString(), messageId.toString());
        for (UUID eventId : List.of(recoverableId, advancedCursorId, changedErrorId, liveId)) {
            jdbcTemplate.update("""
                    INSERT INTO automation_outbox
                        (id, aggregate_id, event_type, created_at, published_at, delivery_attempts,
                         next_attempt_at, last_error, dead_at, claimed_by, claim_until,
                         delivery_page_cursor)
                    VALUES (?, ?, 'AutomationRunCompleted', ?, NULL, 10,
                            NULL, 'IllegalStateException', ?, NULL, NULL, 0)
                    """, eventId.toString(), runId.toString(), Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()));
        }
        jdbcTemplate.update("UPDATE automation_outbox SET delivery_page_cursor = 1 WHERE id = ?",
                advancedCursorId.toString());
        jdbcTemplate.update("UPDATE automation_outbox SET last_error = 'HttpServerErrorException' WHERE id = ?",
                changedErrorId.toString());
        jdbcTemplate.update("UPDATE automation_outbox SET dead_at = NULL WHERE id = ?",
                liveId.toString());

        assertThat(repository.redriveRecoverableDownloadCompletion(recoverableId)).isTrue();
        assertThat(repository.redriveRecoverableDownloadCompletion(advancedCursorId)).isFalse();
        assertThat(repository.redriveRecoverableDownloadCompletion(changedErrorId)).isFalse();
        assertThat(repository.redriveRecoverableDownloadCompletion(liveId)).isFalse();
        var recovered = jdbcTemplate.queryForMap("""
                SELECT delivery_attempts, last_error, dead_at, delivery_page_cursor
                FROM automation_outbox WHERE id = ?
                """, recoverableId.toString());
        assertThat(recovered.get("delivery_attempts")).isEqualTo(0);
        assertThat(recovered.get("last_error")).isNull();
        assertThat(recovered.get("dead_at")).isNull();
        assertThat(recovered.get("delivery_page_cursor")).isEqualTo(0);
    }

    @Test
    void shouldClaimFenceDeferredDeliveryWithoutIncrementingAttempts() {
        JdbcTemplate jdbcTemplate = outboxJdbcTemplate();
        AutomationRepository repository = new AutomationRepository(jdbcTemplate, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_run (id, inbound_message_id, status, action_count)
                VALUES (?, ?, 'SUCCEEDED', 1)
                """, runId.toString(), messageId.toString());
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, created_at, published_at, delivery_attempts,
                     next_attempt_at, last_error, dead_at, claimed_by, claim_until)
                VALUES (?, ?, 'AutomationRunCompleted', ?, NULL, 0, NULL, NULL, NULL, NULL, NULL)
                """, eventId.toString(), runId.toString(), Timestamp.from(Instant.now()));

        AutomationRepository.CompletionEvent firstClaim =
                repository.claimUnpublishedCompletions(10).getFirst();
        Instant deferredAt = Instant.now();
        assertThat(repository.deferOutboxDelivery(eventId, firstClaim.claimToken(),
                Duration.ofHours(1), "HttpClientErrorException")).isTrue();
        var deferred = jdbcTemplate.queryForMap("""
                SELECT delivery_attempts, next_attempt_at, last_error, claimed_by, claim_until
                FROM automation_outbox WHERE id = ?
                """, eventId.toString());
        assertThat(deferred.get("delivery_attempts")).isEqualTo(0);
        assertThat(deferred.get("last_error")).isEqualTo("HttpClientErrorException");
        assertThat(deferred.get("claimed_by")).isNull();
        assertThat(deferred.get("claim_until")).isNull();
        Timestamp nextAttemptAt = (Timestamp) deferred.get("next_attempt_at");
        assertThat(Duration.between(deferredAt, nextAttemptAt.toInstant()).toMillis())
                .isBetween(59_000L, 61_500L);
        assertThat(repository.claimUnpublishedCompletions(10)).isEmpty();

        jdbcTemplate.update("UPDATE automation_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());
        AutomationRepository.CompletionEvent secondClaim =
                repository.claimUnpublishedCompletions(10).getFirst();
        assertThat(repository.deferOutboxDelivery(eventId, firstClaim.claimToken(),
                Duration.ofSeconds(10), "HttpClientErrorException")).isFalse();
        assertThat(repository.deferOutboxDelivery(eventId, secondClaim.claimToken(),
                Duration.ZERO, "HttpClientErrorException")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM automation_outbox WHERE id = ?",
                Integer.class, eventId.toString())).isZero();
    }

    @Test
    void shouldApplyBoundedRetryAndDeadLetterToDuplicateFeedback() {
        JdbcTemplate jdbcTemplate = outboxJdbcTemplate();
        AutomationRepository repository = new AutomationRepository(jdbcTemplate, new ObjectMapper());
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO automation_run (id, inbound_message_id, status, action_count)
                VALUES (?, ?, 'SUCCEEDED', 0)
                """, runId.toString(), messageId.toString());
        jdbcTemplate.update("""
                INSERT INTO automation_outbox
                    (id, aggregate_id, event_type, created_at, published_at, delivery_attempts,
                     next_attempt_at, last_error, dead_at, claimed_by, claim_until)
                VALUES (?, ?, 'AutomationDuplicateLinksDetected', ?, NULL, 0,
                        NULL, NULL, NULL, NULL, NULL)
                """, eventId.toString(), runId.toString(), Timestamp.from(Instant.now()));

        AutomationRepository.CompletionEvent claim =
                repository.claimUnpublishedCompletions(10).getFirst();

        assertThat(claim.duplicateOnly()).isTrue();
        assertThat(repository.recordOutboxFailure(
                eventId, claim.claimToken(), 1, "IllegalStateException")).isTrue();
        assertThat(repository.countDeadCompletions()).isOne();
        assertThat(repository.findUnpublishedCompletions(10)).isEmpty();
    }

    @Test
    void shouldRelayQqCompletionToOriginalPassiveMessage() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 1, 0, "qq-claim");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 21L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:platform-message", "qq_main:c2c:user", "user",
                null, "https://example.test/file", Instant.now(), Instant.now()));
        UUID downloadId = UUID.randomUUID();
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source",
                        "video.mp4", downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 12, 12, List.of(new DownloadIngestionClient.DownloadItem(
                "video.mp4", "TAGGED", List.of(new DownloadIngestionClient.DownloadTag(
                "cosplay", "topic", 0.98))))));
        when(repository.advanceOutboxDeliveryPage(eventId, "qq-claim", 0)).thenReturn(true);

        String pageKey = "automation-completion-" + runId + "-page-1";
        when(messagingClient.reply(messageId, pageKey,
                "下载与标签已完成，共 1 个文件：\n\n1. video.mp4\n   标签：cosplay（topic，0.98）"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(messageId, pageKey,
                "下载与标签已完成，共 1 个文件：\n\n1. video.mp4\n   标签：cosplay（topic，0.98）");
        verify(repository).advanceOutboxDeliveryPage(eventId, "qq-claim", 0);
        verify(repository).markOutboxPublished(eventId, "qq-claim");
    }

    @Test
    void shouldPublishSuccessfulEmptySummaryAsNoNewFiles() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "batch", downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 0, 0, List.of()));
        when(messagingClient.reply(messageId, runId,
                "处理完成，没有新增文件（内容可能已处理）。")).thenReturn(
                new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "处理完成，没有新增文件（内容可能已处理）。");
        verify(repository).markOutboxPublished(eventId, null);
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq(null), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void shouldIncludeSuccessfulFilesAndFailedActionsInPartialCompletion() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        eventId, runId, messageId, "PARTIAL_FAILED", 2)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source-a",
                        "saved.jpg", downloadId, "SUCCEEDED", 100),
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 1, "DOWNLOAD_REQUEST", "source-b",
                        "failed.zip", null, "FAILED", -1)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 12, 12, List.of(new DownloadIngestionClient.DownloadItem(
                "saved.jpg", "TAGGED", List.of(new DownloadIngestionClient.DownloadTag(
                "photo", "topic", 0.95))))));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载处理部分失败，共 2 个文件：\n\n1. saved.jpg\n   标签：photo（topic，0.95）"
                        + "\n\n2. failed.zip\n   标签：失败（下载未完成）");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldFallbackFailedActionWhileRemoteSummaryIsStillRunning() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "timed-out.zip",
                        downloadId, "FAILED", -1)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "RUNNING", 50, 0, 0, List.of()));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(downloadClient).summary(downloadId, 21L);
        verify(messagingClient).reply(messageId, runId,
                "下载处理失败，共 1 个文件：\n\n1. timed-out.zip\n   标签：失败（下载未完成）");
        verify(repository).markOutboxPublished(eventId, null);
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq(null), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void shouldUseSucceededRemoteSummaryForLocallyFailedAction() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "late-success.jpg",
                        downloadId, "FAILED", 80)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(downloadId, "SUCCEEDED", 100, 24, 24,
                        List.of(new DownloadIngestionClient.DownloadItem(
                                "late-success.jpg", "TAGGED", List.of(
                                new DownloadIngestionClient.DownloadTag(
                                        "photo", "topic", 0.97))))));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载与标签已完成，共 1 个文件：\n\n1. late-success.jpg"
                        + "\n   标签：photo（topic，0.97）");
        verify(repository).markOutboxPublished(eventId, null);
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq(null), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void shouldFallbackFailedActionWhenRemoteSummaryIsUnavailable() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "unavailable.zip",
                        downloadId, "FAILED", 50)));
        when(downloadClient.summary(downloadId, 21L)).thenThrow(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载处理失败，共 1 个文件：\n\n1. unavailable.zip\n   标签：失败（下载未完成）");
        verify(repository).markOutboxPublished(eventId, null);
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq(null), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void shouldPreserveTooEarlyDeferralForLocallyFailedActionSummary() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "FAILED", 1, 0, "failed-summary-425");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "deferred.zip",
                        downloadId, "FAILED", 50)));
        when(downloadClient.summary(downloadId, 21L)).thenThrow(
                clientError(HttpStatus.TOO_EARLY, "9"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).deferOutboxDelivery(
                eventId, "failed-summary-425", Duration.ofSeconds(9), "HttpClientErrorException");
        verify(messagingClient, never()).reply(eq(messageId), any(String.class), any(String.class));
        verify(repository, never()).markOutboxPublished(eventId, "failed-summary-425");
        verify(repository, never()).recordOutboxFailure(
                eq(eventId), eq("failed-summary-425"), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void shouldRejectFailedRemoteSummaryForLocallySucceededAction() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "conflict.jpg",
                        downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "FAILED", 100, 0, 0, List.of()));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient, never()).reply(eq(messageId), eq(runId), any());
        verify(repository, never()).markOutboxPublished(eventId, null);
        verify(repository).recordOutboxFailure(eventId, null, 10, "IllegalStateException");
    }

    @Test
    void shouldDegradeTerminalFailedActionWhenSummaryHasNoItems() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "failed.zip", downloadId, "FAILED", -1)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "FAILED", 0, 0, 0, List.of()));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载处理失败，共 1 个文件：\n\n1. failed.zip\n   标签：失败（下载未完成）");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldPreserveSavedBatchItemsWhenRemainingDownloadFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_BATCH", "source", "message-url-batch-0",
                        downloadId, "FAILED", 45)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(downloadId, "FAILED", 45, 24, 32,
                        List.of(
                                new DownloadIngestionClient.DownloadItem(
                                        "saved-a.jpg", "TAGGED", List.of(
                                        new DownloadIngestionClient.DownloadTag(
                                                "photo", "topic", 0.95))),
                                new DownloadIngestionClient.DownloadItem(
                                        "saved-b.png", "FAILED", List.of()))));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(downloadClient).summary(downloadId, 21L);
        verify(messagingClient).reply(messageId, runId,
                "下载处理失败，共 2 个文件：\n\n1. saved-a.jpg\n   标签：photo（topic，0.95）"
                        + "\n\n2. saved-b.png\n   标签：失败（文件已保存）");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldPreserveSavedAttachmentItemWhenRemainingDownloadIsCancelled() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID attachmentJobId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "CANCELLED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "ATTACHMENT_DOWNLOAD", "source", "attachment.bin",
                        attachmentJobId, "CANCELLED", 35)));
        when(messagingClient.attachment(attachmentJobId, 21L)).thenReturn(
                new MessagingClient.AttachmentSnapshot(
                        attachmentJobId, "CANCELLED", downloadId));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(downloadId, "CANCELLED", 35, 16, 24,
                        List.of(new DownloadIngestionClient.DownloadItem(
                                "saved-before-cancel.jpg", "SKIPPED", List.of()))));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).attachment(attachmentJobId, 21L);
        verify(downloadClient).summary(downloadId, 21L);
        verify(messagingClient).reply(messageId, runId,
                "下载处理已取消，共 1 个文件：\n\n1. saved-before-cancel.jpg"
                        + "\n   标签：已跳过（文件不支持）");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldDeliverMixedCompletionWhenOneMetadataLookupFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID goodId = UUID.randomUUID();
        UUID unavailableId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 2)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source-a",
                        "tagged.jpg", goodId, "SUCCEEDED", 100),
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 1, "DOWNLOAD_REQUEST", "source-b",
                        "saved.jpg", unavailableId, "SUCCEEDED", 100)));
        when(downloadClient.summary(goodId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                goodId, "SUCCEEDED", 100, 12, 12, List.of(new DownloadIngestionClient.DownloadItem(
                "tagged.jpg", "TAGGED", List.of(new DownloadIngestionClient.DownloadTag(
                "photo", "topic", 0.95))))));
        when(downloadClient.summary(unavailableId, 21L)).thenThrow(
                new HttpClientErrorException(HttpStatus.NOT_FOUND));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载处理已完成，共 2 个文件：\n\n1. tagged.jpg\n   标签：photo（topic，0.95）"
                        + "\n\n2. saved.jpg\n   标签：标签信息不可用（文件已保存）");
        verify(repository).markOutboxPublished(eventId, null);
        verify(repository, never()).markOutboxDead(eq(eventId), eq(null),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldRetryTransientMetadataFailureAndContinueBatch() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        UUID goodEventId = UUID.randomUUID();
        UUID goodRunId = UUID.randomUUID();
        UUID goodMessageId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1),
                new AutomationRepository.CompletionEvent(
                        goodEventId, goodRunId, goodMessageId, "SUCCEEDED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.get(goodMessageId)).thenReturn(message(goodMessageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source-a",
                        "first.jpg", downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId, 21L)).thenThrow(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient, never()).reply(eq(messageId), eq(runId),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).markOutboxPublished(eventId, null);
        verify(repository).recordOutboxFailure(eventId, null, 10, "HttpServerErrorException");
        verify(repository).markOutboxPublished(goodEventId, null);
    }

    @Test
    void shouldRetryPendingTagSummaryWithoutPublishingCompletion() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source",
                        "pending.jpg", downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 12, 12, List.of(new DownloadIngestionClient.DownloadItem(
                "pending.jpg", "PENDING", List.of()))));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(messagingClient, never()).reply(eq(messageId), eq(runId),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).markOutboxPublished(eventId, null);
        verify(repository).recordOutboxFailure(eventId, null, 10, "IllegalStateException");
    }

    @Test
    void shouldDeliverAnotherEventWhileOneSummaryIsSlow() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID slowEventId = UUID.randomUUID();
        UUID slowRunId = UUID.randomUUID();
        UUID slowMessageId = UUID.randomUUID();
        UUID slowDownloadId = UUID.randomUUID();
        UUID fastEventId = UUID.randomUUID();
        UUID fastRunId = UUID.randomUUID();
        UUID fastMessageId = UUID.randomUUID();
        CountDownLatch fastDelivered = new CountDownLatch(1);
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        slowEventId, slowRunId, slowMessageId, "SUCCEEDED", 1),
                new AutomationRepository.CompletionEvent(
                        fastEventId, fastRunId, fastMessageId, "SUCCEEDED", 1)));
        when(messagingClient.get(slowMessageId)).thenReturn(message(slowMessageId));
        when(messagingClient.get(fastMessageId)).thenReturn(message(fastMessageId));
        when(repository.findActionExecutions(slowRunId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source",
                        "slow.jpg", slowDownloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(slowDownloadId, 21L)).thenAnswer(invocation -> {
            assertThat(fastDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            return new DownloadIngestionClient.DownloadSummary(
                    slowDownloadId, "SUCCEEDED", 100, 1, 1,
                    List.of(new DownloadIngestionClient.DownloadItem(
                            "slow.jpg", "SKIPPED", List.of())));
        });
        when(messagingClient.reply(fastMessageId, fastRunId,
                "下载处理已完成，共 1 个文件。")).thenAnswer(invocation -> {
                    fastDelivered.countDown();
                    return new MessagingClient.InboundReplySnapshot(
                            fastMessageId, "EMAIL", "ACCEPTED");
                });

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        verify(repository).markOutboxPublished(slowEventId, null);
        verify(repository).markOutboxPublished(fastEventId, null);
    }

    @Test
    void shouldBoundLargeEventWindowAndNotStarveAnotherEvent() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID slowEventId = UUID.randomUUID();
        UUID slowRunId = UUID.randomUUID();
        UUID slowMessageId = UUID.randomUUID();
        UUID fastEventId = UUID.randomUUID();
        UUID fastRunId = UUID.randomUUID();
        UUID fastMessageId = UUID.randomUUID();
        CountDownLatch fastDelivered = new CountDownLatch(1);
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        slowEventId, slowRunId, slowMessageId, "SUCCEEDED", 500),
                new AutomationRepository.CompletionEvent(
                        fastEventId, fastRunId, fastMessageId, "SUCCEEDED", 1)));
        when(messagingClient.get(slowMessageId)).thenReturn(message(slowMessageId));
        when(messagingClient.get(fastMessageId)).thenReturn(message(fastMessageId));
        when(repository.findActionExecutions(slowRunId)).thenReturn(
                java.util.stream.IntStream.range(0, 500)
                        .mapToObj(index -> new AutomationRepository.ActionExecution(
                                UUID.randomUUID(), index, "DOWNLOAD_REQUEST", "source",
                                "slow-" + index, UUID.randomUUID(), "SUCCEEDED", 100))
                        .toList());
        when(downloadClient.summary(any(), eq(21L))).thenAnswer(invocation -> {
            assertThat(fastDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
        });
        when(messagingClient.reply(fastMessageId, fastRunId,
                "下载处理已完成，共 1 个文件。")).thenAnswer(invocation -> {
                    fastDelivered.countDown();
                    return new MessagingClient.InboundReplySnapshot(
                            fastMessageId, "EMAIL", "ACCEPTED");
                });

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(downloadClient, atMost(2)).summary(any(), eq(21L));
        verify(repository).recordOutboxFailure(
                slowEventId, null, 10, "HttpServerErrorException");
        verify(repository).markOutboxPublished(fastEventId, null);
    }

    @Test
    void shouldPaginateLargeCompletionWithoutDroppingFileDetails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 40)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source",
                        "batch", downloadId, "SUCCEEDED", 100)));
        List<DownloadIngestionClient.DownloadItem> items = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> new DownloadIngestionClient.DownloadItem(
                        "file-" + index + "-" + "x".repeat(80) + ".jpg", "TAGGED",
                        List.of(new DownloadIngestionClient.DownloadTag("tag-" + index, "topic", 0.98))))
                .toList();
        when(downloadClient.summary(downloadId, 21L)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 40, 40, items));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, downloadClient).relay();

        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(messagingClient, org.mockito.Mockito.atLeast(2)).reply(eq(messageId), key.capture(), body.capture());
        assertThat(body.getAllValues()).allMatch(value -> value.length() <= 1800);
        String combined = String.join("\n", body.getAllValues());
        assertThat(combined).contains("file-0-", "file-39-");
        verify(repository).markOutboxPublished(eventId, null);
    }

    @Test
    void shouldResumeAtFirstUnacknowledgedCompletionPageAfterPartialFailure() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        var firstClaim = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 40, 0, "claim-1");
        var retryClaim = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 40, 1, "claim-2");
        when(repository.claimUnpublishedCompletions(4))
                .thenReturn(List.of(firstClaim))
                .thenReturn(List.of(retryClaim));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "batch", downloadId, "SUCCEEDED", 100)));
        List<DownloadIngestionClient.DownloadItem> items = java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> new DownloadIngestionClient.DownloadItem(
                        "file-" + index + "-" + "x".repeat(80) + ".jpg", "TAGGED",
                        List.of(new DownloadIngestionClient.DownloadTag(
                                "tag-" + index, "topic", 0.98))))
                .toList();
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "SUCCEEDED", 100, 40, 40, items));
        when(repository.advanceOutboxDeliveryPage(
                eq(eventId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        String pageTwoKey = "automation-completion-" + runId + "-page-2";
        when(messagingClient.reply(eq(messageId), eq(pageTwoKey),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(
                        messageId, "EMAIL", "ACCEPTED"));

        CompletionOutboxRelay relay = new CompletionOutboxRelay(
                repository, properties(true), messagingClient, downloadClient);
        relay.relay();
        relay.relay();

        String pageOneKey = "automation-completion-" + runId + "-page-1";
        verify(messagingClient, org.mockito.Mockito.times(1)).reply(
                eq(messageId), eq(pageOneKey), org.mockito.ArgumentMatchers.anyString());
        verify(messagingClient, org.mockito.Mockito.times(2)).reply(
                eq(messageId), eq(pageTwoKey), org.mockito.ArgumentMatchers.anyString());
        verify(repository).advanceOutboxDeliveryPage(eventId, "claim-1", 0);
        verify(repository, never()).advanceOutboxDeliveryPage(eventId, "claim-1", 1);
        verify(repository, never()).advanceOutboxDeliveryPage(eventId, "claim-2", 0);
        verify(repository).recordOutboxFailure(
                eventId, "claim-1", 10, "IllegalStateException");
        verify(repository).markOutboxPublished(eventId, "claim-2");
    }

    @Test
    void shouldResumeMultiPageFallbackForLocallyFailedActions() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var firstClaim = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "FAILED", 18, 0, "fallback-claim-1");
        var retryClaim = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "FAILED", 18, 1, "fallback-claim-2");
        when(repository.claimUnpublishedCompletions(4))
                .thenReturn(List.of(firstClaim))
                .thenReturn(List.of(retryClaim));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        List<AutomationRepository.ActionExecution> actions = java.util.stream.IntStream.range(0, 18)
                .mapToObj(index -> new AutomationRepository.ActionExecution(
                        UUID.randomUUID(), index, "DOWNLOAD_REQUEST", "source",
                        "fallback-" + index + "-" + "x".repeat(100) + ".jpg",
                        UUID.randomUUID(), "FAILED", 50))
                .toList();
        when(repository.findActionExecutions(runId)).thenReturn(actions);
        when(downloadClient.summary(any(UUID.class), eq(21L))).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSummary(
                        invocation.getArgument(0), "RUNNING", 50, 0, 0, List.of()));
        when(repository.advanceOutboxDeliveryPage(
                eq(eventId), any(String.class), any(Integer.class))).thenReturn(true);
        String pageTwoKey = "automation-completion-" + runId + "-page-2";
        when(messagingClient.reply(eq(messageId), eq(pageTwoKey), any(String.class)))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(
                        messageId, "EMAIL", "ACCEPTED"));

        CompletionOutboxRelay relay = new CompletionOutboxRelay(
                repository, properties(true), messagingClient, downloadClient);
        relay.relay();
        relay.relay();

        String pageOneKey = "automation-completion-" + runId + "-page-1";
        verify(messagingClient, org.mockito.Mockito.times(1)).reply(
                eq(messageId), eq(pageOneKey), any(String.class));
        verify(messagingClient, org.mockito.Mockito.times(2)).reply(
                eq(messageId), eq(pageTwoKey), any(String.class));
        verify(repository).advanceOutboxDeliveryPage(eventId, "fallback-claim-1", 0);
        verify(repository, never()).advanceOutboxDeliveryPage(
                eventId, "fallback-claim-2", 0);
        verify(repository).markOutboxPublished(eventId, "fallback-claim-2");
    }

    @Test
    void shouldPublishWithoutResendingWhenEveryPageWasAlreadyAcknowledged() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 1, 1, "claim-1");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient, never()).reply(
                eq(messageId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(messagingClient, never()).reply(
                eq(messageId), eq(runId), org.mockito.ArgumentMatchers.anyString());
        verify(repository).markOutboxPublished(eventId, "claim-1");
    }

    @Test
    void shouldStopImmediatelyWhenPageCursorAdvanceLosesClaim() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(
                eventId, runId, messageId, "SUCCEEDED", 1, 0, "stale-claim");
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(
                messageId, runId, "下载处理已完成，共 1 个文件。");
        verify(repository).advanceOutboxDeliveryPage(eventId, "stale-claim", 0);
        verify(repository, never()).markOutboxPublished(
                org.mockito.ArgumentMatchers.eq(eventId),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).recordOutboxFailure(
                org.mockito.ArgumentMatchers.eq(eventId),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldDeliverAllFourteenQqFilesWithoutTruncation() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        eventId, runId, messageId, "SUCCEEDED", 14, 0, "qq-claim")));
        when(messagingClient.get(messageId)).thenReturn(qqMessage(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "batch", downloadId, "SUCCEEDED", 100)));
        List<DownloadIngestionClient.DownloadItem> items = java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> new DownloadIngestionClient.DownloadItem(
                        "qq-file-" + index + "-" + "x".repeat(80) + ".jpg", "TAGGED",
                        List.of(new DownloadIngestionClient.DownloadTag(
                                "tag-" + index, "topic", 0.98))))
                .toList();
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "SUCCEEDED", 100, 14, 14, items));
        when(repository.advanceOutboxDeliveryPage(
                eq(eventId), eq("qq-claim"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        org.mockito.ArgumentCaptor<String> keys =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> bodies =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(messagingClient, org.mockito.Mockito.atLeastOnce()).reply(
                eq(messageId), keys.capture(), bodies.capture());
        assertThat(keys.getAllValues()).hasSizeLessThanOrEqualTo(8)
                .allMatch(key -> key.matches("automation-completion-" + runId + "-page-[1-8]"));
        String combined = String.join("\n", bodies.getAllValues());
        for (int index = 0; index < 14; index++) {
            assertThat(combined).contains("qq-file-" + index + "-");
        }
        assertThat(combined).doesNotContain("后续内容已截断");
        org.mockito.ArgumentCaptor<Integer> cursors =
                org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(repository, org.mockito.Mockito.times(keys.getAllValues().size()))
                .advanceOutboxDeliveryPage(eq(eventId), eq("qq-claim"), cursors.capture());
        assertThat(cursors.getAllValues()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, keys.getAllValues().size()).boxed().toList());
        verify(repository).markOutboxPublished(eventId, "qq-claim");
    }

    @Test
    void shouldCapQqCompletionAtEightStablePassivePagesWithExplicitTruncation() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(
                        eventId, runId, messageId, "SUCCEEDED", 160, 0, "qq-claim")));
        when(messagingClient.get(messageId)).thenReturn(qqMessage(messageId));
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0,
                        "DOWNLOAD_REQUEST", "source", "batch", downloadId, "SUCCEEDED", 100)));
        List<DownloadIngestionClient.DownloadItem> items = java.util.stream.IntStream.range(0, 160)
                .mapToObj(index -> new DownloadIngestionClient.DownloadItem(
                        "qq-large-" + index + "-" + "x".repeat(100) + ".jpg", "TAGGED",
                        List.of(new DownloadIngestionClient.DownloadTag(
                                "tag-" + index, "topic", 0.98))))
                .toList();
        when(downloadClient.summary(downloadId, 21L)).thenReturn(
                new DownloadIngestionClient.DownloadSummary(
                        downloadId, "SUCCEEDED", 100, 160, 160, items));
        when(repository.advanceOutboxDeliveryPage(
                eq(eventId), eq("qq-claim"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        org.mockito.ArgumentCaptor<String> keys =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> bodies =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(messagingClient, org.mockito.Mockito.times(8)).reply(
                eq(messageId), keys.capture(), bodies.capture());
        assertThat(keys.getAllValues()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 8)
                        .mapToObj(page -> "automation-completion-" + runId + "-page-" + page)
                        .toList());
        assertThat(bodies.getAllValues().getLast())
                .contains("后续内容已截断", "请在系统中查询完整结果")
                .hasSizeLessThanOrEqualTo(1700);
        assertThat(String.join("\n", bodies.getAllValues()))
                .contains("qq-large-0-")
                .doesNotContain("qq-large-159-");
        for (int cursor = 0; cursor < 8; cursor++) {
            verify(repository).advanceOutboxDeliveryPage(eventId, "qq-claim", cursor);
        }
        verify(repository).markOutboxPublished(eventId, "qq-claim");
    }

    @Test
    void shouldLimitConfiguredCompletionBatchToAvailableExternalPermits() throws Exception {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        List<AutomationRepository.CompletionEvent> events = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> new AutomationRepository.CompletionEvent(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", 1))
                .toList();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(events);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstFourEntered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(messagingClient.get(any(UUID.class))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstFourEntered.countDown();
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                return message(invocation.getArgument(0));
            } finally {
                active.decrementAndGet();
            }
        });
        when(messagingClient.reply(any(UUID.class), any(UUID.class), any(String.class)))
                .thenAnswer(invocation -> new MessagingClient.InboundReplySnapshot(
                        invocation.getArgument(0), "QQ", "ACCEPTED"));
        AutomationProperties batchProperties = new AutomationProperties(
                "internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", true, 50, 100, 10, 10000);

        CompletableFuture<Void> relay = CompletableFuture.runAsync(
                () -> new CompletionOutboxRelay(
                        repository, batchProperties, messagingClient, downloadClient).relay());
        try {
            assertThat(firstFourEntered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        relay.get(5, TimeUnit.SECONDS);

        assertThat(peak).hasValue(4);
        verify(repository).claimUnpublishedCompletions(4);
        verify(repository, never()).claimUnpublishedCompletions(50);
        verify(messagingClient, org.mockito.Mockito.times(4)).get(any(UUID.class));
    }

    @Test
    void shouldAllowFourConcurrentActionsForSingleCompletionEvent() throws Exception {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.claimUnpublishedCompletions(4)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 5)));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        List<AutomationRepository.ActionExecution> actions = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new AutomationRepository.ActionExecution(
                        UUID.randomUUID(), index, "DOWNLOAD_REQUEST", "source",
                        "parallel-" + index + ".jpg", UUID.randomUUID(), "SUCCEEDED", 100))
                .toList();
        when(repository.findActionExecutions(runId)).thenReturn(actions);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstFourEntered = new CountDownLatch(4);
        CountDownLatch fifthEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(downloadClient.summary(any(UUID.class), eq(21L))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            if (firstFourEntered.getCount() > 0) {
                firstFourEntered.countDown();
            } else {
                fifthEntered.countDown();
            }
            try {
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                UUID requestId = invocation.getArgument(0);
                return new DownloadIngestionClient.DownloadSummary(
                        requestId, "SUCCEEDED", 100, 1, 1,
                        List.of(new DownloadIngestionClient.DownloadItem(
                                "parallel.jpg", "SKIPPED", List.of())));
            } finally {
                active.decrementAndGet();
            }
        });

        CompletableFuture<Void> relay = CompletableFuture.runAsync(
                () -> new CompletionOutboxRelay(
                        repository, properties(true), messagingClient, downloadClient).relay());
        try {
            assertThat(firstFourEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fifthEntered.await(250, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown();
        }
        relay.get(5, TimeUnit.SECONDS);

        assertThat(peak).hasValue(4);
        assertThat(fifthEntered.getCount()).isZero();
        verify(downloadClient, org.mockito.Mockito.times(5)).summary(any(UUID.class), eq(21L));
    }

    private AutomationProperties properties(boolean enabled) {
        return new AutomationProperties("internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token",
                enabled, 10, 100, 10, 10000);
    }

    private InboundMessage message(UUID messageId) {
        return new InboundMessage(messageId, 21L, ChannelType.EMAIL, "external-1", "thread-1",
                "owner@example.test", null, "download", Instant.now(), Instant.now());
    }

    private InboundMessage qqMessage(UUID messageId) {
        return new InboundMessage(messageId, 21L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:platform-message", "qq_main:c2c:user", "user",
                null, "download", Instant.now(), Instant.now());
    }

    private HttpClientErrorException clientError(HttpStatus status, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return HttpClientErrorException.create(
                status, status.getReasonPhrase(), headers, new byte[0], StandardCharsets.UTF_8);
    }

    private JdbcTemplate outboxJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:completion_outbox_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE automation_run (
                    id VARCHAR(36) PRIMARY KEY,
                    inbound_message_id VARCHAR(36) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    action_count INT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE automation_outbox (
                    id VARCHAR(36) PRIMARY KEY,
                    aggregate_id VARCHAR(36) NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
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
        return jdbcTemplate;
    }
}
