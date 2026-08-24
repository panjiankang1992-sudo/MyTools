package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动化完成通知中继测试。
 */
class CompletionOutboxRelayTest {

    @Test
    void shouldConfirmOutboxOnlyAfterMessagingAcceptsDelivery() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 2);
        when(repository.findUnpublishedEmailCompletions(10)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.createCompletionEmail(runId, 21L, "owner@example.test", "SUCCEEDED", 2))
                .thenReturn(new MessagingClient.DeliverySnapshot(UUID.randomUUID(), "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient).relay();

        verify(repository).markOutboxPublished(eventId);
    }

    @Test
    void shouldRetainOutboxWhenMessagingFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.findUnpublishedEmailCompletions(10)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenThrow(new IllegalStateException("temporary failure"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient).relay();

        verify(repository, never()).markOutboxPublished(eventId);
    }

    private AutomationProperties properties(boolean enabled) {
        return new AutomationProperties("internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", enabled, 10);
    }

    private InboundMessage message(UUID messageId) {
        return new InboundMessage(messageId, 21L, ChannelType.EMAIL, "external-1", "thread-1",
                "owner@example.test", null, "download", Instant.now(), Instant.now());
    }
}
