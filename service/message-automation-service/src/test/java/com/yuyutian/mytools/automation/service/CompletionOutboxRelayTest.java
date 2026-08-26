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
        QQConnectorClient qqConnectorClient = mock(QQConnectorClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 2);
        when(repository.findUnpublishedCompletions(ChannelType.EMAIL, 10)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.createCompletionEmail(runId, 21L, "owner@example.test", "SUCCEEDED", 2))
                .thenReturn(new MessagingClient.DeliverySnapshot(UUID.randomUUID(), "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, qqConnectorClient).relay();

        verify(repository).markOutboxPublished(eventId);
    }

    @Test
    void shouldRetainOutboxWhenMessagingFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        QQConnectorClient qqConnectorClient = mock(QQConnectorClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.findUnpublishedCompletions(ChannelType.EMAIL, 10)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenThrow(new IllegalStateException("temporary failure"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, qqConnectorClient).relay();

        verify(repository, never()).markOutboxPublished(eventId);
    }

    @Test
    void shouldRelayQqCompletionToOriginalPassiveMessage() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        QQConnectorClient qqConnectorClient = mock(QQConnectorClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1);
        when(repository.findUnpublishedCompletions(ChannelType.QQ, 10)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 21L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:platform-message", "qq_main:c2c:user", "user",
                null, "https://example.test/file", Instant.now(), Instant.now()));

        new CompletionOutboxRelay(repository, properties(true), messagingClient, qqConnectorClient).relay();

        verify(qqConnectorClient).send("user", "platform-message", "下载任务已完成，共 1 项。");
        verify(repository).markOutboxPublished(eventId);
    }

    private AutomationProperties properties(boolean enabled) {
        return new AutomationProperties("internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", "http://qq.test", "qq-token",
                enabled, 10, 100);
    }

    private InboundMessage message(UUID messageId) {
        return new InboundMessage(messageId, 21L, ChannelType.EMAIL, "external-1", "thread-1",
                "owner@example.test", null, "download", Instant.now(), Instant.now());
    }
}
