package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.InboundMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @MockBean
    private MessagingClient messagingClient;

    @MockBean
    private DownloadIngestionClient downloadClient;

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
        when(downloadClient.get(any())).thenAnswer(invocation ->
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
        when(downloadClient.cancel(any())).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "CANCELLED"));

        var running = service.process(messageId);
        var cancelled = service.cancel(running.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.actions()).extracting("status").containsOnly("CANCELLED");
        verify(downloadClient, times(2)).cancel(any());
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
        when(downloadClient.get(downloadId)).thenReturn(
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
