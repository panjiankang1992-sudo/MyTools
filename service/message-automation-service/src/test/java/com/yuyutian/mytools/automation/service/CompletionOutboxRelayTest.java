package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

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
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 2);
        when(repository.findUnpublishedCompletions(10)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(message(messageId));
        when(messagingClient.reply(messageId, runId, "下载处理已完成，共 2 个文件。"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(messageId, "EMAIL", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository).markOutboxPublished(eventId);
    }

    @Test
    void shouldRetainOutboxWhenMessagingFails() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(repository.findUnpublishedCompletions(10)).thenReturn(List.of(
                new AutomationRepository.CompletionEvent(eventId, runId, messageId, "FAILED", 1)));
        when(messagingClient.get(messageId)).thenThrow(new IllegalStateException("temporary failure"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(repository, never()).markOutboxPublished(eventId);
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
        when(repository.findUnpublishedCompletions(10)).thenReturn(List.of(
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

        verify(repository).markOutboxPublished(badEventId);
        verify(repository).markOutboxPublished(goodEventId);
    }

    @Test
    void shouldRelayQqCompletionToOriginalPassiveMessage() {
        AutomationRepository repository = mock(AutomationRepository.class);
        MessagingClient messagingClient = mock(MessagingClient.class);
        DownloadIngestionClient downloadClient = mock(DownloadIngestionClient.class);
        UUID eventId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var event = new AutomationRepository.CompletionEvent(eventId, runId, messageId, "SUCCEEDED", 1);
        when(repository.findUnpublishedCompletions(10)).thenReturn(List.of(event));
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 21L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:platform-message", "qq_main:c2c:user", "user",
                null, "https://example.test/file", Instant.now(), Instant.now()));
        UUID downloadId = UUID.randomUUID();
        when(repository.findActionExecutions(runId)).thenReturn(List.of(
                new AutomationRepository.ActionExecution(UUID.randomUUID(), 0, "DOWNLOAD_REQUEST", "source",
                        "video.mp4", downloadId, "SUCCEEDED", 100)));
        when(downloadClient.summary(downloadId)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "SUCCEEDED", 100, 12, 12, List.of(new DownloadIngestionClient.DownloadItem(
                "video.mp4", "TAGGED", List.of(new DownloadIngestionClient.DownloadTag(
                "cosplay", "topic", 0.98))))));

        when(messagingClient.reply(messageId, runId,
                "下载与标签已完成，共 1 个文件：\n\n1. video.mp4\n   标签：cosplay（topic，0.98）"))
                .thenReturn(new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        new CompletionOutboxRelay(repository, properties(true), messagingClient,
                downloadClient).relay();

        verify(messagingClient).reply(messageId, runId,
                "下载与标签已完成，共 1 个文件：\n\n1. video.mp4\n   标签：cosplay（topic，0.98）");
        verify(repository).markOutboxPublished(eventId);
    }

    private AutomationProperties properties(boolean enabled) {
        return new AutomationProperties("internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token",
                enabled, 10, 100);
    }

    private InboundMessage message(UUID messageId) {
        return new InboundMessage(messageId, 21L, ChannelType.EMAIL, "external-1", "thread-1",
                "owner@example.test", null, "download", Instant.now(), Instant.now());
    }
}
