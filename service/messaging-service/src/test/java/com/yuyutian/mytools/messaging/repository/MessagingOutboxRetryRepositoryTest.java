package com.yuyutian.mytools.messaging.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:messaging_outbox_retry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "messaging.one-bot-ingress-enabled=false",
        "messaging.automation-relay-enabled=false"
})
class MessagingOutboxRetryRepositoryTest {

    @Autowired
    private MessagingRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOutbox() {
        jdbcTemplate.update("DELETE FROM messaging_outbox");
    }

    @Test
    void shouldBackoffAndStopAfterFiveFailures() {
        UUID eventId = insertEvent();
        String oversizedErrorCode = "E".repeat(256);

        var firstClaim = claimInboundEvent(eventId);
        assertThat(repository.recordInboundOutboxFailure(
                eventId, firstClaim.claimToken(), 5, oversizedErrorCode))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.RETRY_SCHEDULED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CHAR_LENGTH(last_error_code) FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isEqualTo(128);
        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30))).isEmpty();

        for (int attempt = 2; attempt <= 4; attempt++) {
            makeRetryDue(eventId);
            var claim = claimInboundEvent(eventId);
            assertThat(repository.recordInboundOutboxFailure(eventId, claim.claimToken(), 5, null))
                    .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.RETRY_SCHEDULED);
        }
        makeRetryDue(eventId);
        var finalClaim = claimInboundEvent(eventId);
        assertThat(repository.recordInboundOutboxFailure(
                eventId, finalClaim.claimToken(), 5, "FinalError"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.DEAD);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NULL FROM messaging_outbox WHERE id = ?", Boolean.class,
                eventId.toString())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dead_at IS NOT NULL FROM messaging_outbox WHERE id = ?", Boolean.class,
                eventId.toString())).isTrue();
        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void shouldTreatMissingOrPublishedEventAsLostClaim() {
        assertThat(repository.recordInboundOutboxFailure(
                UUID.randomUUID(), "missing-claim", 5, "Missing"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.LOST_CLAIM);

        UUID eventId = insertEvent();
        jdbcTemplate.update("UPDATE messaging_outbox SET published_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), eventId.toString());

        assertThat(repository.recordInboundOutboxFailure(
                eventId, "stale-claim", 5, "AlreadyPublished"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.LOST_CLAIM);
    }

    @Test
    void shouldExposeAndIdempotentlyRedriveExactDeadInboundEvent() {
        UUID eventId = insertEvent();
        var claim = claimInboundEvent(eventId);
        assertThat(repository.recordInboundOutboxFailure(
                eventId, claim.claimToken(), 1, "TransientFailure"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.DEAD);
        assertThat(repository.countDeadMessageReceivedEvents()).isEqualTo(1);

        var first = repository.redriveDeadInboundEvent(eventId).orElseThrow();
        var replay = repository.redriveDeadInboundEvent(eventId).orElseThrow();

        assertThat(first.eventId()).isEqualTo(eventId);
        assertThat(first.eventType()).isEqualTo("MessageReceived");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.redriven()).isTrue();
        assertThat(replay.status()).isEqualTo("PENDING");
        assertThat(replay.redriven()).isFalse();
        assertThat(repository.countDeadMessageReceivedEvents()).isZero();
        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30)))
                .extracting("id").contains(eventId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM messaging_outbox WHERE id = ?", String.class,
                eventId.toString())).isEqualTo("TransientFailure");
    }

    @Test
    void shouldCountAcceptanceDeadLetterAndRejectUnrelatedRedrive() {
        UUID acceptanceId = insertEvent("OneBotForwardAccepted");
        var acceptanceClaim = repository.claimUnpublishedOneBotAcceptanceEvents(
                10, Duration.ofSeconds(30)).getFirst();
        assertThat(repository.recordInboundOutboxFailure(
                acceptanceId, acceptanceClaim.claimToken(), 1, "ReplyRejected"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.DEAD);
        assertThat(repository.countDeadOneBotAcceptanceEvents()).isEqualTo(1);

        UUID deliveryId = insertEvent("MessageDeliveryRequested");
        jdbcTemplate.update("UPDATE messaging_outbox SET dead_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), deliveryId.toString());

        assertThat(repository.redriveDeadInboundEvent(deliveryId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dead_at IS NOT NULL FROM messaging_outbox WHERE id = ?", Boolean.class,
                deliveryId.toString())).isTrue();
    }

    @Test
    void shouldFenceStaleWorkerAndRecoverExpiredClaim() {
        UUID eventId = insertEvent();
        var firstClaim = claimInboundEvent(eventId);

        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30))).isEmpty();
        jdbcTemplate.update("UPDATE messaging_outbox SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());
        var replacementClaim = claimInboundEvent(eventId);

        assertThat(replacementClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(repository.markOutboxPublished(eventId, firstClaim.claimToken())).isFalse();
        assertThat(repository.recordInboundOutboxFailure(
                eventId, firstClaim.claimToken(), 5, "LateFailure"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.LOST_CLAIM);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isZero();
        assertThat(repository.markOutboxPublished(eventId, replacementClaim.claimToken())).isTrue();
    }

    @Test
    void shouldAllowOnlyOneConcurrentClaimAcrossInstances() throws Exception {
        UUID eventId = insertEvent();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return repository.claimUnpublishedInboundEvents(1, Duration.ofSeconds(30));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return repository.claimUnpublishedInboundEvents(1, Duration.ofSeconds(30));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            start.countDown();
            var claimed = new java.util.ArrayList<MessagingRepository.OutboxEvent>();
            claimed.addAll(first.get(5, TimeUnit.SECONDS));
            claimed.addAll(second.get(5, TimeUnit.SECONDS));

            assertThat(claimed).extracting(MessagingRepository.OutboxEvent::id).containsExactly(eventId);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldAcceptJustExpiredClaimUntilAnotherWorkerTakesOver() {
        UUID eventId = insertEvent();
        var claim = claimInboundEvent(eventId);
        jdbcTemplate.update("UPDATE messaging_outbox SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());

        assertThat(repository.markOutboxPublished(eventId, claim.claimToken())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM messaging_outbox WHERE id = ?", Boolean.class,
                eventId.toString())).isTrue();
    }

    @Test
    void shouldConsumeFailureBudgetOnlyOnceForOneClaim() {
        UUID eventId = insertEvent();
        var claim = claimInboundEvent(eventId);

        assertThat(repository.recordInboundOutboxFailure(
                eventId, claim.claimToken(), 5, "TemporaryFailure"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.RETRY_SCHEDULED);
        assertThat(repository.recordInboundOutboxFailure(
                eventId, claim.claimToken(), 5, "DuplicateFailure"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.LOST_CLAIM);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_attempts FROM messaging_outbox WHERE id = ?", Integer.class,
                eventId.toString())).isEqualTo(1);
    }

    @Test
    void shouldGateMessageUntilAcceptanceIsPublished() {
        UUID messageId = UUID.randomUUID();
        UUID terminalId = insertEvent("MessageReceived", messageId);
        UUID acceptanceId = insertEvent("OneBotForwardAccepted", messageId);

        var acceptanceClaim = repository.claimUnpublishedOneBotAcceptanceEvents(
                10, Duration.ofSeconds(30)).stream()
                .filter(event -> event.id().equals(acceptanceId)).findFirst().orElseThrow();
        assertThat(repository.claimUnpublishedOneBotAcceptanceEvents(10, Duration.ofSeconds(30)))
                .noneMatch(event -> event.id().equals(acceptanceId));
        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30)))
                .noneMatch(event -> event.id().equals(terminalId));

        jdbcTemplate.update("UPDATE messaging_outbox SET claim_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), acceptanceId.toString());
        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30)))
                .noneMatch(event -> event.id().equals(terminalId));
        var replacementClaim = repository.claimUnpublishedOneBotAcceptanceEvents(
                10, Duration.ofSeconds(30)).stream()
                .filter(event -> event.id().equals(acceptanceId)).findFirst().orElseThrow();
        assertThat(repository.markOutboxPublished(acceptanceId, replacementClaim.claimToken())).isTrue();

        assertThat(repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30)))
                .extracting("id").contains(terminalId);
        assertThat(repository.markOutboxPublished(acceptanceId, acceptanceClaim.claimToken())).isFalse();
    }

    @Test
    void shouldApplyClaimMigrationOnH2() {
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_name) = 'messaging_outbox'
                  AND LOWER(column_name) IN ('claim_token', 'claim_until')
                """, Integer.class);
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.indexes
                WHERE LOWER(index_name) = 'idx_messaging_outbox_claim'
                """, Integer.class);

        assertThat(columnCount).isEqualTo(2);
        assertThat(indexCount).isEqualTo(1);
    }

    private UUID insertEvent() {
        return insertEvent("MessageReceived");
    }

    private UUID insertEvent(String eventType) {
        return insertEvent(eventType, UUID.randomUUID());
    }

    private UUID insertEvent(String eventType, UUID messageId) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO messaging_outbox
                    (id, aggregate_type, aggregate_id, event_type, payload_json, created_at, published_at)
                VALUES (?, 'INBOUND_MESSAGE', ?, ?, '{}', ?, NULL)
                """, eventId.toString(), messageId.toString(), eventType, Timestamp.from(Instant.now()));
        return eventId;
    }

    private MessagingRepository.OutboxEvent claimInboundEvent(UUID eventId) {
        return repository.claimUnpublishedInboundEvents(10, Duration.ofSeconds(30)).stream()
                .filter(event -> event.id().equals(eventId)).findFirst().orElseThrow();
    }

    private void makeRetryDue(UUID eventId) {
        jdbcTemplate.update("UPDATE messaging_outbox SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), eventId.toString());
    }
}
