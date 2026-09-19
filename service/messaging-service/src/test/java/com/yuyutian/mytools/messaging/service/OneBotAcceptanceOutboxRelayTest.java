package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.CreateInboundReplyRequest;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OneBot 合并转发受理回复中继测试。
 */
class OneBotAcceptanceOutboxRelayTest {

    @Test
    void shouldReplyWithStableIdempotencyKeyBeforeMarkingPublished() {
        MessagingRepository repository = mock(MessagingRepository.class);
        InboundReplyService replyService = mock(InboundReplyService.class);
        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String idempotencyKey = "onebot-forward-accepted:" + messageId;
        String body = "Received. Forwarded content has been stored and is being analyzed.";
        String claimToken = UUID.randomUUID().toString();
        when(repository.claimUnpublishedOneBotAcceptanceEvents(4, Duration.ofSeconds(30)))
                .thenReturn(List.of(new MessagingRepository.OneBotAcceptanceEvent(
                        eventId, messageId, idempotencyKey, body, true, claimToken)), List.of());
        when(repository.markOutboxPublished(eventId, claimToken)).thenReturn(true);
        OneBotAcceptanceOutboxRelay relay = new OneBotAcceptanceOutboxRelay(
                repository, replyService, properties());

        relay.relay();
        relay.close();

        verify(replyService).reply(messageId, new CreateInboundReplyRequest(idempotencyKey, body));
        verify(repository).markOutboxPublished(eventId, claimToken);
    }

    @Test
    void shouldBoundPermanentReplyRejectionWithoutAutomaticLoop() {
        MessagingRepository repository = mock(MessagingRepository.class);
        InboundReplyService replyService = mock(InboundReplyService.class);
        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String idempotencyKey = "onebot-forward-accepted:" + messageId;
        String body = "Received. Forwarded content has been stored and is being analyzed.";
        String claimToken = UUID.randomUUID().toString();
        when(repository.claimUnpublishedOneBotAcceptanceEvents(4, Duration.ofSeconds(30)))
                .thenReturn(List.of(new MessagingRepository.OneBotAcceptanceEvent(
                        eventId, messageId, idempotencyKey, body, true, claimToken)), List.of());
        when(replyService.reply(messageId, new CreateInboundReplyRequest(
                idempotencyKey, body)))
                .thenThrow(new InboundReplyRejectedException(new IllegalStateException("rejected")));
        when(repository.recordInboundOutboxFailure(
                eventId, claimToken, 1, "InboundReplyRejectedException"))
                .thenReturn(MessagingRepository.InboundOutboxFailureOutcome.DEAD);
        OneBotAcceptanceOutboxRelay relay = new OneBotAcceptanceOutboxRelay(
                repository, replyService, properties());

        relay.relay();
        relay.close();

        verify(repository).recordInboundOutboxFailure(
                eventId, claimToken, 1, "InboundReplyRejectedException");
    }

    @Test
    void shouldDeadLetterInvalidPersistedPayloadWithoutCallingProvider() {
        MessagingRepository repository = mock(MessagingRepository.class);
        InboundReplyService replyService = mock(InboundReplyService.class);
        UUID eventId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String claimToken = UUID.randomUUID().toString();
        when(repository.claimUnpublishedOneBotAcceptanceEvents(4, Duration.ofSeconds(30)))
                .thenReturn(List.of(new MessagingRepository.OneBotAcceptanceEvent(
                        eventId, messageId, "", "", false, claimToken)), List.of());
        when(repository.recordInboundOutboxFailure(
                eventId, claimToken, 1, "InvalidAcceptancePayload"))
                .thenReturn(MessagingRepository.InboundOutboxFailureOutcome.DEAD);
        OneBotAcceptanceOutboxRelay relay = new OneBotAcceptanceOutboxRelay(
                repository, replyService, properties());

        relay.relay();
        relay.close();

        verify(repository).recordInboundOutboxFailure(eventId, claimToken, 1, "InvalidAcceptancePayload");
        verify(replyService, org.mockito.Mockito.never()).reply(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private MessagingProperties properties() {
        return new MessagingProperties("http://scheduler", "messaging-token", "sender@example.com",
                "http://automation.test", "automation-token", true, 10, 30, true,
                "http://download.test", "download-token", "http://resolver.test", "resolver-token",
                "http://qq.test", "qq-token", "http://telegram.test", "telegram-token");
    }
}
