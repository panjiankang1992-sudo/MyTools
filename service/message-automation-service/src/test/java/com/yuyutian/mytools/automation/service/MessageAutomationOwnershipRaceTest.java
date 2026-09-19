package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.AutomationActionView;
import com.yuyutian.mytools.automation.model.AutomationRuleRecord;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageAutomationOwnershipRaceTest {

    @Mock
    private AutomationRepository repository;

    @Mock
    private MessagingClient messagingClient;

    @Mock
    private DownloadIngestionClient downloadClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    @Test
    void shouldNotReplanRunCommittedAfterInitialLookupMiss() {
        UUID messageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        Instant now = Instant.now();
        AutomationRuleRecord rule = new AutomationRuleRecord(
                ruleId, 41L, "qq_download", ChannelType.QQ, null, "sender-41", "",
                100, true, 1, "HTTP_ASSET", 10, now, now);
        AutomationActionView action = new AutomationActionView(
                UUID.randomUUID(), runId, 0, "DOWNLOAD_REQUEST", null,
                "CREATING", null, now, now);
        AutomationRunView authoritative = new AutomationRunView(
                runId, messageId, ruleId, 1, "RUNNING", 1, List.of(),
                null, now, now, List.of(action));
        InboundMessage message = new InboundMessage(
                messageId, 41L, ChannelType.QQ, "external-41", "qq_main:c2c:sender-41",
                "sender-41", null, "https://files.example/race.zip", now, now);

        // 模拟 B 首次未命中，随后 A 提交完整运行，B 在事务内只取得既有运行。
        when(repository.findRun(messageId)).thenReturn(Optional.empty());
        when(messagingClient.get(messageId)).thenReturn(message);
        when(repository.findEnabledRules(41L, ChannelType.QQ)).thenReturn(List.of(rule));
        when(repository.beginRun(messageId, rule)).thenReturn(
                new AutomationRepository.RunStart(authoritative, false));
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        MessageAutomationService service = new MessageAutomationService(
                repository, messagingClient, downloadClient,
                new TransactionTemplate(transactionManager),
                new AutomationProperties("internal", "http://messaging", "messaging-token",
                        "http://download", "download-token", true, 10, 10, 3, 25));

        AutomationRunView replay = service.process(messageId);

        assertThat(replay).isEqualTo(authoritative);
        assertThat(replay.status()).isEqualTo("RUNNING");
        assertThat(replay.actions()).containsExactly(action);
        verify(repository, never()).claimLink(anyLong(), any(), any(), any(), any());
        verify(repository, never()).createAction(any(), anyInt(), any(), any(), any());
        verify(repository, never()).completeDuplicateOnlyRun(any());
        verify(messagingClient, never()).reply(any(UUID.class), anyString(), anyString());
    }
}
