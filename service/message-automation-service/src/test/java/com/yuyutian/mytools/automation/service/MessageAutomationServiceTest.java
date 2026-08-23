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
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> "download-" + invocation.getArgument(3));

        var completed = service.process(messageId);
        var duplicate = service.process(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actionRefs()).containsExactly("download-0", "download-1");
        assertThat(duplicate.id()).isEqualTo(completed.id());
        verify(downloadClient, times(2)).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_run WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_outbox WHERE aggregate_id = ?",
                Integer.class, completed.id().toString())).isEqualTo(1);
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

    private InboundMessage message(UUID id, long ownerId, String conversation, String sender, String body) {
        Instant now = Instant.now();
        return new InboundMessage(id, ownerId, ownerId == 11L ? ChannelType.TELEGRAM : ChannelType.EMAIL,
                "external-" + id, conversation, sender, null, body, now, now);
    }
}
