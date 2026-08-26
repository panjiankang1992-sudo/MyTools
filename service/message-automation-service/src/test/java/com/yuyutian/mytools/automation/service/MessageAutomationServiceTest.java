package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class MessageAutomationServiceTest {

    @Autowired
    private MessageAutomationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AutomationRepository repository;

    @MockBean
    private MessagingClient messagingClient;

    @MockBean
    private DownloadIngestionClient downloadClient;

    @Test
    void shouldRejectChangedRuleReplay() {
        CreateAutomationRuleRequest request = new CreateAutomationRuleRequest(10L, "stable_rule",
                ChannelType.EMAIL, "thread-1", "allowed@example.com", "download: ",
                "HTTP_ASSET", 1, 100, true);

        var created = service.createRule(request);
        var replayed = service.createRule(request);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThatThrownBy(() -> service.createRule(new CreateAutomationRuleRequest(10L, "stable_rule",
                ChannelType.EMAIL, "thread-1", "allowed@example.com", "other: ",
                "HTTP_ASSET", 1, 100, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldCreateBoundedDownloadsForAuthorizedMessageOnlyOnce() {
        service.createRule(new CreateAutomationRuleRequest(11L, "telegram_download", ChannelType.TELEGRAM,
                "chat-7", "user-9", "/download ", "HTTP_ASSET", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(message(messageId, 11L, "chat-7", "user-9",
                "/download https://files.example/a.zip https://files.example/b.zip https://files.example/c.zip"));
        List<UUID> downloadIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> downloadIds.get(invocation.getArgument(3)).toString());
        when(downloadClient.get(any(), anyLong())).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "SUCCEEDED"));

        var running = service.process(messageId);
        var duplicate = service.process(messageId);

        assertThat(running.status()).isEqualTo("SUCCEEDED");
        assertThat(duplicate.status()).isEqualTo("SUCCEEDED");
        assertThat(duplicate.actionRefs()).containsExactly(downloadIds.get(0).toString(), downloadIds.get(1).toString());
        assertThat(duplicate.id()).isEqualTo(running.id());
        verify(downloadClient, times(2)).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_run WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_outbox WHERE aggregate_id = ?",
                Integer.class, running.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_action WHERE automation_run_id = ?",
                Integer.class, running.id().toString())).isEqualTo(2);
    }

    @Test
    void shouldExposeTerminalEmailRunForReliableNotification() {
        service.createRule(new CreateAutomationRuleRequest(16L, "email_completion", ChannelType.EMAIL,
                "thread-16", "owner@example.test", "download: ", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 16L, ChannelType.EMAIL,
                "external-" + messageId, "thread-16", "owner@example.test", null,
                "download: https://files.example/archive.zip", Instant.now(), Instant.now()));
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 16L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "SUCCEEDED"));

        var run = service.process(messageId);
        var events = repository.findUnpublishedCompletions(ChannelType.EMAIL, 10);

        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.runId()).isEqualTo(run.id());
            assertThat(event.messageId()).isEqualTo(messageId);
            assertThat(event.status()).isEqualTo("SUCCEEDED");
            assertThat(event.actionCount()).isEqualTo(1);
        });
    }

    @Test
    void shouldRejectMessageOutsideAuthorizedSenderScope() {
        service.createRule(new CreateAutomationRuleRequest(12L, "email_download", ChannelType.EMAIL,
                "thread-2", "allowed@example.com", "download: ", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(message(messageId, 12L, "thread-2",
                "attacker@example.com", "download: https://files.example/private.zip"));

        var completed = service.process(messageId);

        assertThat(completed.status()).isEqualTo("NO_MATCH");
        assertThat(completed.actionCount()).isZero();
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldCancelEveryRunningDownloadAction() {
        service.createRule(new CreateAutomationRuleRequest(13L, "cancel_download", ChannelType.EMAIL,
                "thread-3", "allowed@example.com", "download: ", "HTTP_ASSET", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 13L, ChannelType.EMAIL,
                "external-" + messageId, "thread-3", "allowed@example.com", null,
                "download: https://files.example/a https://files.example/b", Instant.now(), Instant.now()));
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> UUID.randomUUID().toString());
        when(downloadClient.cancel(any(), anyLong())).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "CANCELLED"));

        var running = service.process(messageId);
        var cancelled = service.cancel(running.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.actions()).extracting("status").containsOnly("CANCELLED");
        verify(downloadClient, times(2)).cancel(any(), org.mockito.ArgumentMatchers.eq(13L));
    }

    @Test
    void shouldCreateReconcileAndCancelStandardAttachmentAction() {
        service.createRule(new CreateAutomationRuleRequest(15L, "attachment_download", ChannelType.ONEBOT,
                "group-1", "user-1", "/save", "MESSAGE_ATTACHMENT", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        InboundMessage message = new InboundMessage(messageId, 15L, ChannelType.ONEBOT,
                "external-" + messageId, "group-1", "user-1", null, "/save", Instant.now(), Instant.now(),
                List.of(new InboundMessage.MessagePart(partId, 0, "ATTACHMENT", "FILE",
                        "book.epub", "application/epub+zip", 1024L)));
        when(messagingClient.get(messageId)).thenReturn(message);
        when(messagingClient.createAttachment(messageId, partId, 15L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "QUEUED"));
        when(messagingClient.attachment(jobId, 15L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "RUNNING"));
        when(messagingClient.cancelAttachment(jobId, 15L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "CANCELLED"));

        var running = service.process(messageId);
        var cancelled = service.cancel(running.id());

        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.actions()).extracting("actionType").containsExactly("ATTACHMENT_DOWNLOAD");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
        verify(messagingClient).cancelAttachment(jobId, 15L);
    }

    @Test
    void shouldPreferStructuredAttachmentForUrlRule() {
        service.createRule(new CreateAutomationRuleRequest(17L, "qq_download", ChannelType.QQ,
                null, "qq-user", "", "HTTP_ASSET", 5, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        InboundMessage message = new InboundMessage(messageId, 17L, ChannelType.QQ,
                "external-" + messageId, "qq:c2c:qq-user", "qq-user", null,
                "https://files.example/download", Instant.now(), Instant.now(),
                List.of(new InboundMessage.MessagePart(partId, 1, "ATTACHMENT", "IMAGE",
                        "photo.png", "image/png", 1024L)));
        when(messagingClient.get(messageId)).thenReturn(message);
        when(messagingClient.createAttachment(messageId, partId, 17L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "QUEUED"));
        when(messagingClient.attachment(jobId, 17L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "SUCCEEDED"));

        var completed = service.process(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actions()).extracting("actionType").containsExactly("ATTACHMENT_DOWNLOAD");
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
        verify(messagingClient).createAttachment(messageId, partId, 17L);
    }

    @Test
    void shouldRecoverUnknownDownloadCreationByStableActionSequence() {
        service.createRule(new CreateAutomationRuleRequest(14L, "recover_download", ChannelType.EMAIL,
                "thread-4", "allowed@example.com", "download: ", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        InboundMessage inbound = new InboundMessage(messageId, 14L, ChannelType.EMAIL,
                "external-" + messageId, "thread-4", "allowed@example.com", null,
                "download: https://files.example/recover", Instant.now(), Instant.now());
        when(messagingClient.get(messageId)).thenReturn(inbound);
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("unknown result")).thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 14L)).thenReturn(
                new DownloadIngestionClient.DownloadSnapshot(downloadId, "SUCCEEDED"));

        var uncertain = service.process(messageId);
        var recovered = service.process(messageId);
        var reconciled = service.process(messageId);

        assertThat(uncertain.status()).isEqualTo("RUNNING");
        assertThat(recovered.status()).isEqualTo("RUNNING");
        assertThat(reconciled.status()).isEqualTo("SUCCEEDED");
        assertThat(reconciled.actions()).extracting("externalRequestId").containsExactly(downloadId);
        verify(downloadClient, times(2)).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
    }

    private InboundMessage message(UUID id, long ownerId, String conversation, String sender, String body) {
        Instant now = Instant.now();
        return new InboundMessage(id, ownerId, ownerId == 11L ? ChannelType.TELEGRAM : ChannelType.EMAIL,
                "external-" + id, conversation, sender, null, body, now, now);
    }
}
