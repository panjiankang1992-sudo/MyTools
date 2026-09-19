package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.config.AutomationProperties;
import com.yuyutian.mytools.automation.model.ChannelType;
import com.yuyutian.mytools.automation.model.CreateAutomationRuleRequest;
import com.yuyutian.mytools.automation.model.InboundMessage;
import com.yuyutian.mytools.automation.model.AutomationRunView;
import com.yuyutian.mytools.automation.repository.AutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "automation.max-actions-per-message=25")
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
    void shouldPlanMagnetSeparatelyFromHttpBatchAndIgnoreTrackerLinks() {
        service.createRule(new CreateAutomationRuleRequest(931L, "magnet_mixed", ChannelType.EMAIL,
                "magnet-chat", "magnet-user", "", "HTTP_ASSET", 10, 100, true));
        UUID messageId = UUID.randomUUID();
        String magnet = "magnet:?xt=urn:btih:" + "a".repeat(40)
                + "&tr=https%3A%2F%2Ftracker.example%2Fannounce";
        when(messagingClient.get(messageId)).thenReturn(message(messageId, 931L, "magnet-chat", "magnet-user",
                "https://files.example/a.jpg https://files.example/b.jpg " + magnet + " " + magnet
                        + " https://files.example/c.jpg"));
        var run = service.process(messageId);
        var actions = repository.findActions(run.id());
        assertThat(actions).extracting("actionType")
                .containsExactly("DOWNLOAD_BATCH", "DOWNLOAD_REQUEST", "DOWNLOAD_REQUEST");
        assertThat(jdbcTemplate.queryForObject("SELECT source_url FROM automation_action WHERE id = ?",
                String.class, actions.get(1).id().toString())).isEqualTo(magnet);
        assertThat(run.actionCount()).isEqualTo(3);
    }

    @Test
    void shouldPersistCreatingPlanAndDeferSubmissionUntilReconciliation() {
        service.createRule(new CreateAutomationRuleRequest(11L, "telegram_download", ChannelType.TELEGRAM,
                "chat-7", "user-9", "/download ", "HTTP_ASSET", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(message(messageId, 11L, "chat-7", "user-9",
                "/download https://files.example/a.zip https://files.example/b.zip https://files.example/c.zip"));
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(any(), anyLong())).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "SUCCEEDED"));

        var accepted = service.process(messageId);
        var duplicate = service.process(messageId);

        assertThat(accepted.status()).isEqualTo("RUNNING");
        assertThat(accepted.actionCount()).isEqualTo(1);
        assertThat(accepted.actions()).extracting("status").containsExactly("CREATING");
        assertThat(duplicate.id()).isEqualTo(accepted.id());
        assertThat(duplicate.actions()).extracting("status").containsExactly("CREATING");
        verify(downloadClient, never()).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());
        verify(messagingClient, never()).createAttachment(any(), any(), anyLong());
        verify(messagingClient).get(messageId);
        verify(messagingClient).reply(messageId, "automation-start-" + accepted.id(),
                "已收到，正在处理；完成后会发送文件名和标签信息。");

        var submitted = reconcile(messageId);
        var completed = reconcile(messageId);

        assertThat(submitted.status()).isEqualTo("RUNNING");
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actionRefs()).containsExactly(downloadId.toString());
        verify(downloadClient).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_run WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_outbox WHERE aggregate_id = ?",
                Integer.class, accepted.id().toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_action WHERE automation_run_id = ?",
                Integer.class, accepted.id().toString())).isEqualTo(1);
    }

    @Test
    void shouldNotSendSecondAcknowledgementForPreAcknowledgedInbound() {
        service.createRule(new CreateAutomationRuleRequest(31L, "onebot_pre_ack",
                ChannelType.QQ, "qq-onebot:c2c:user-31", "user-31", "", "HTTP_ASSET",
                1, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(
                messageId, 31L, ChannelType.QQ, "onebot:event-31",
                "qq-onebot:c2c:user-31", "user-31", null,
                "https://files.example/pre-ack.zip", Instant.now(), Instant.now(),
                List.of(), true));

        AutomationRunView accepted = service.process(messageId);

        assertThat(accepted.status()).isEqualTo("RUNNING");
        verify(messagingClient, never()).reply(
                eq(messageId), startsWith("automation-start-"), anyString());
    }

    @Test
    void shouldPersistTerminalCompletionWhenPreAcknowledgedForwardDoesNotMatchRule() {
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(
                messageId, 310L, ChannelType.QQ, "onebot:forward-310",
                "qq-onebot:c2c:user-310", "user-310", null,
                "forwarded content without a configured rule", Instant.now(), Instant.now(),
                List.of(), true));

        AutomationRunView completed = service.process(messageId);

        assertThat(completed.status()).isEqualTo("NO_MATCH");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ? AND event_type = 'AutomationRunCompleted'
                """, Integer.class, completed.id().toString())).isEqualTo(1);
        verify(messagingClient, never()).reply(
                eq(messageId), startsWith("automation-start-"), anyString());
    }

    @Test
    void shouldKeepGetPureWhileRunIsWaitingForBackgroundReconciliation() {
        UUID messageId = prepareSingleDownload(311L, "pure_read", "thread-311");
        AutomationRunView accepted = service.process(messageId);
        clearInvocations(messagingClient, downloadClient);

        AutomationRunView queried = service.get(messageId);

        assertThat(queried.id()).isEqualTo(accepted.id());
        assertThat(queried.actions()).extracting("status").containsExactly("CREATING");
        verify(messagingClient, never()).get(any());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
        verify(downloadClient, never()).get(any(), anyLong());
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
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 16L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "SUCCEEDED"));

        service.process(messageId);
        reconcile(messageId);
        var run = reconcile(messageId);
        // 历史迁移运行可能没有保留规则关联，完成通知仍须按入站消息原渠道投递。
        jdbcTemplate.update("UPDATE automation_run SET automation_rule_id = NULL WHERE id = ?", run.id().toString());
        var events = repository.findUnpublishedCompletions(10);

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
        var replay = service.process(messageId);

        assertThat(completed.status()).isEqualTo("NO_MATCH");
        assertThat(replay.id()).isEqualTo(completed.id());
        assertThat(completed.actionCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_outbox WHERE aggregate_id = ?",
                Integer.class, completed.id().toString())).isZero();
        verify(messagingClient).get(messageId);
        verify(messagingClient, never()).reply(any(), anyString(), anyString());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldCancelEveryRunningDownloadAction() {
        service.createRule(new CreateAutomationRuleRequest(13L, "cancel_download", ChannelType.EMAIL,
                "thread-3", "allowed@example.com", "download: ", "HTTP_ASSET", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 13L, ChannelType.EMAIL,
                "external-" + messageId, "thread-3", "allowed@example.com", null,
                "download: https://files.example/a https://files.example/b", Instant.now(), Instant.now()));
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString()))
                .thenReturn(downloadId.toString());
        when(downloadClient.cancel(any(), anyLong())).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "CANCELLED"));

        service.process(messageId);
        var running = reconcile(messageId);
        var cancelled = service.cancel(running.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.actions()).extracting("status").containsOnly("CANCELLED");
        verify(downloadClient).cancel(downloadId, 13L);
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

        service.process(messageId);
        var running = reconcile(messageId);
        var cancelled = service.cancel(running.id());

        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.actions()).extracting("actionType").containsExactly("ATTACHMENT_DOWNLOAD");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any());
        verify(messagingClient).cancelAttachment(jobId, 15L);
    }

    @Test
    void shouldLimitAttachmentsByRuleActionBudget() {
        service.createRule(new CreateAutomationRuleRequest(19L, "telegram_album_download", ChannelType.TELEGRAM,
                "chat-19", "user-19", "", "MESSAGE_ATTACHMENT", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        List<InboundMessage.MessagePart> parts = IntStream.range(0, 8)
                .mapToObj(index -> new InboundMessage.MessagePart(UUID.randomUUID(), index, "ATTACHMENT",
                        index < 6 ? "IMAGE" : "VIDEO", "media-" + index, null, null))
                .toList();
        InboundMessage message = new InboundMessage(messageId, 19L, ChannelType.TELEGRAM,
                "telegram-album-19", "chat-19", "user-19", null, "", Instant.now(), Instant.now(), parts);
        when(messagingClient.get(messageId)).thenReturn(message);
        when(messagingClient.createAttachment(eq(messageId), any(), eq(19L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(UUID.randomUUID(), "QUEUED"));
        when(messagingClient.attachment(any(), eq(19L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(
                        invocation.getArgument(0), "SUCCEEDED"));

        var accepted = service.process(messageId);

        assertThat(accepted.actions()).extracting("status").containsOnly("CREATING");
        verify(messagingClient, never()).createAttachment(any(), any(), anyLong());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());

        reconcile(messageId);
        var completed = reconcile(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actionCount()).isEqualTo(1);
        assertThat(completed.actions()).extracting("actionType").containsOnly("ATTACHMENT_DOWNLOAD");
        verify(messagingClient).createAttachment(eq(messageId), any(), eq(19L));
    }

    @Test
    void shouldProcessStructuredAttachmentAndBodyUrlIndependently() {
        service.createRule(new CreateAutomationRuleRequest(17L, "qq_download", ChannelType.QQ,
                null, "qq-user", "", "HTTP_ASSET", 5, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        InboundMessage message = new InboundMessage(messageId, 17L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:platform-message", "qq:c2c:qq-user", "qq-user", null,
                "https://files.example/download", Instant.now(), Instant.now(),
                List.of(new InboundMessage.MessagePart(partId, 1, "ATTACHMENT", "IMAGE",
                        "photo.png", "image/png", 1024L)));
        when(messagingClient.get(messageId)).thenReturn(message);
        when(messagingClient.createAttachment(messageId, partId, 17L))
                .thenAnswer(invocation -> {
                    // 任何下载调用发生前，附件和正文链接的分析结果都必须已经入库。
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM automation_action WHERE automation_run_id = "
                                    + "(SELECT id FROM automation_run WHERE inbound_message_id = ?)",
                            Integer.class, messageId.toString())).isEqualTo(2);
                    return new MessagingClient.AttachmentSnapshot(jobId, "QUEUED");
                });
        when(messagingClient.attachment(jobId, 17L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(jobId, "SUCCEEDED"));
        when(downloadClient.create(eq(messageId), eq(17L), any(), eq(1), eq("HTTP_ASSET"),
                eq("https://files.example/download"), anyString(), any()))
                .thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 17L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));

        var accepted = service.process(messageId);

        assertThat(accepted.status()).isEqualTo("RUNNING");
        assertThat(accepted.actions()).extracting("status").containsOnly("CREATING");
        verify(messagingClient, never()).createAttachment(any(), any(), anyLong());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());

        reconcile(messageId);
        reconcile(messageId);
        reconcile(messageId);
        var completed = reconcile(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actions()).extracting("actionType")
                .containsExactly("ATTACHMENT_DOWNLOAD", "DOWNLOAD_REQUEST");
        verify(messagingClient).createAttachment(messageId, partId, 17L);
    }

    @Test
    void shouldNotPlanActionsForForwardedAttachmentFileNames() {
        service.createRule(new CreateAutomationRuleRequest(41L, "qq_forwarded_attachment_names", ChannelType.QQ,
                null, "qq-user-41", "", "HTTP_ASSET", 25, 100, true));
        UUID messageId = UUID.randomUUID();
        String body = """
                [群聊的聊天记录]
                === 消息 1 ===
                [附件1] 类型:图片 文件名:EF84ED0FA716AB1DA73605C418C51E24.jpg 尺寸:1280x1707 大小:173.4KB URL:
                [附件2] 类型:图片 文件名:526CF7E97548618E84D32105E3DB12CF.jpg 尺寸:1280x1707 大小:142.3KB URL:
                """;
        List<InboundMessage.MessagePart> parts = List.of(
                new InboundMessage.MessagePart(UUID.randomUUID(), 1, "ATTACHMENT", "IMAGE",
                        "EF84ED0FA716AB1DA73605C418C51E24.jpg", "image/jpeg", 177562L),
                new InboundMessage.MessagePart(UUID.randomUUID(), 2, "ATTACHMENT", "IMAGE",
                        "526CF7E97548618E84D32105E3DB12CF.jpg", "image/jpeg", 145715L));
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 41L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:forwarded", "qq:c2c:qq-user-41", "qq-user-41", null,
                body, Instant.now(), Instant.now(), parts));

        var run = service.process(messageId);

        assertThat(run.actionCount()).isEqualTo(2);
        assertThat(repository.findActions(run.id())).extracting("actionType")
                .containsOnly("ATTACHMENT_DOWNLOAD");
        verify(downloadClient, never()).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void shouldFailWithoutActionInputWhenBodyOnlyCarriesFileNames() {
        service.createRule(new CreateAutomationRuleRequest(42L, "qq_file_name_only", ChannelType.QQ,
                null, "qq-user-42", "", "HTTP_ASSET", 25, 100, true));
        UUID messageId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 42L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:names", "qq:c2c:qq-user-42", "qq-user-42", null,
                "CF3A6F3AADF2DB3093CD4FD3CD9269EF.png", Instant.now(), Instant.now()));

        var run = service.process(messageId);

        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("AUTOMATION_002");
        assertThat(run.actionCount()).isZero();
        verify(downloadClient, never()).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());
    }

    @Test
    void shouldKeepRealLinksThatShareAttachmentFileExtensions() {
        service.createRule(new CreateAutomationRuleRequest(43L, "qq_real_links", ChannelType.QQ,
                null, "qq-user-43", "", "HTTP_ASSET", 25, 100, true));
        UUID messageId = UUID.randomUUID();
        String body = """
                [群聊的聊天记录]
                [附件1] 类型:图片 文件名:EF84ED0FA716AB1DA73605C418C51E24.jpg 尺寸:1280x1707 大小:173.4KB URL:
                https://files.example/report.zip
                files.example/album/cover.jpg
                """;
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 43L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:links", "qq:c2c:qq-user-43", "qq-user-43", null,
                body, Instant.now(), Instant.now()));

        var run = service.process(messageId);

        assertThat(repository.findActions(run.id())).extracting("actionType").containsExactly("DOWNLOAD_BATCH");
        String payload = jdbcTemplate.queryForObject(
                "SELECT source_url FROM automation_action WHERE automation_run_id = ?",
                String.class, run.id().toString());
        assertThat(payload).contains("https://files.example/report.zip")
                .contains("https://files.example/album/cover.jpg");
    }

    @Test
    void shouldSplitUrlsIntoStableBatchesApplyServiceLimitAndRecover() {
        service.createRule(new CreateAutomationRuleRequest(22L, "bounded_url_batches", ChannelType.EMAIL,
                "thread-22", "owner22@example.com", "", "HTTP_ASSET", 30, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID firstRequestId = UUID.randomUUID();
        UUID secondRequestId = UUID.randomUUID();
        List<String> urls = IntStream.range(0, 30)
                .mapToObj(index -> "https://files.example/item-" + index).toList();
        Instant receivedAt = Instant.now();
        String body = String.join(" ", urls);
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 22L,
                ChannelType.EMAIL, "external-22", "thread-22", "owner22@example.com", null,
                body, receivedAt, receivedAt));
        when(downloadClient.createBatch(eq(messageId), eq(22L), any(), eq(0),
                eq(urls.subList(0, 20)), eq(receivedAt), eq(body))).thenReturn(firstRequestId.toString());
        when(downloadClient.createBatch(eq(messageId), eq(22L), any(), eq(1),
                eq(urls.subList(20, 25)), eq(receivedAt), eq(body)))
                .thenThrow(new IllegalStateException("unknown result"))
                .thenReturn(secondRequestId.toString());
        when(downloadClient.get(any(), eq(22L))).thenAnswer(invocation ->
                new DownloadIngestionClient.DownloadSnapshot(invocation.getArgument(0), "SUCCEEDED"));

        var accepted = service.process(messageId);
        var duplicate = service.process(messageId);

        assertThat(accepted.actions()).extracting("status").containsOnly("CREATING");
        assertThat(duplicate.actions()).extracting("status").containsOnly("CREATING");
        verify(downloadClient, never()).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());

        var uncertain = reconcile(messageId);
        var failedSubmission = reconcile(messageId);
        makeCreatingActionsDue(messageId);
        reconcile(messageId);
        var completedAfterRetry = reconcile(messageId);
        var replayedCompletion = reconcile(messageId);

        assertThat(uncertain.status()).isEqualTo("RUNNING");
        assertThat(failedSubmission.status()).isEqualTo("RUNNING");
        assertThat(completedAfterRetry.status()).isEqualTo("SUCCEEDED");
        assertThat(replayedCompletion.status()).isEqualTo("SUCCEEDED");
        assertThat(replayedCompletion.actionCount()).isEqualTo(2);
        assertThat(replayedCompletion.actions()).extracting("sequence").containsExactly(0, 1);
        assertThat(replayedCompletion.actions()).extracting("actionType").containsOnly("DOWNLOAD_BATCH");
        verify(downloadClient).createBatch(eq(messageId), eq(22L), any(), eq(0),
                eq(urls.subList(0, 20)), eq(receivedAt), eq(body));
        verify(downloadClient, times(2)).createBatch(eq(messageId), eq(22L), any(), eq(1),
                eq(urls.subList(20, 25)), eq(receivedAt), eq(body));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message_link WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(25);
    }

    @Test
    void shouldShareRuleActionBudgetBetweenAttachmentsAndUrls() {
        service.createRule(new CreateAutomationRuleRequest(23L, "shared_action_budget", ChannelType.QQ,
                null, "qq-budget-user", "", "HTTP_ASSET", 3, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant receivedAt = Instant.now();
        List<InboundMessage.MessagePart> parts = IntStream.range(0, 2)
                .mapToObj(index -> new InboundMessage.MessagePart(UUID.randomUUID(), index, "ATTACHMENT",
                        "IMAGE", "image-" + index + ".png", "image/png", 100L)).toList();
        InboundMessage message = new InboundMessage(messageId, 23L, ChannelType.QQ,
                "external-23", "qq:c2c:qq-budget-user", "qq-budget-user", null,
                "https://files.example/a https://files.example/b https://files.example/c",
                receivedAt, receivedAt, parts);
        when(messagingClient.get(messageId)).thenReturn(message);
        when(messagingClient.createAttachment(eq(messageId), any(), eq(23L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(UUID.randomUUID(), "QUEUED"));
        when(messagingClient.attachment(any(), eq(23L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(
                        invocation.getArgument(0), "SUCCEEDED"));
        when(downloadClient.create(eq(messageId), eq(23L), any(), eq(2), eq("HTTP_ASSET"),
                eq("https://files.example/a"), anyString(), eq(receivedAt))).thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 23L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));

        service.process(messageId);
        reconcile(messageId);
        reconcile(messageId);
        reconcile(messageId);
        reconcile(messageId);
        reconcile(messageId);
        var completed = reconcile(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actionCount()).isEqualTo(3);
        assertThat(completed.actions()).extracting("actionType")
                .containsExactly("ATTACHMENT_DOWNLOAD", "ATTACHMENT_DOWNLOAD", "DOWNLOAD_REQUEST");
        verify(messagingClient, times(2)).createAttachment(eq(messageId), any(), eq(23L));
        verify(downloadClient).create(eq(messageId), eq(23L), any(), eq(2), eq("HTTP_ASSET"),
                eq("https://files.example/a"), anyString(), eq(receivedAt));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message_link WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
    }

    @Test
    void shouldBatchCreatingActionsAndKeepSubmittedPollingBounded() {
        service.createRule(new CreateAutomationRuleRequest(30L, "fair_attachment_reconciliation",
                ChannelType.TELEGRAM, "chat-30", "user-30", "", "MESSAGE_ATTACHMENT", 8, 100, true));
        UUID messageId = UUID.randomUUID();
        List<InboundMessage.MessagePart> parts = IntStream.range(0, 8)
                .mapToObj(index -> new InboundMessage.MessagePart(UUID.randomUUID(), index, "ATTACHMENT",
                        "IMAGE", "image-" + index + ".png", "image/png", 100L)).toList();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 30L,
                ChannelType.TELEGRAM, "external-30", "chat-30", "user-30", null,
                "", Instant.now(), Instant.now(), parts));
        when(messagingClient.createAttachment(eq(messageId), any(), eq(30L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(UUID.randomUUID(), "QUEUED"));
        when(messagingClient.attachment(any(), eq(30L)))
                .thenAnswer(invocation -> new MessagingClient.AttachmentSnapshot(
                        invocation.getArgument(0), "RUNNING"));

        var accepted = service.process(messageId);
        reconcile(messageId);

        assertThat(actionStatusCount(accepted.id(), "RUNNING")).isEqualTo(4);
        assertThat(actionStatusCount(accepted.id(), "CREATING")).isEqualTo(4);
        verify(messagingClient, times(4)).createAttachment(eq(messageId), any(), eq(30L));

        reconcile(messageId);

        assertThat(actionStatusCount(accepted.id(), "RUNNING")).isEqualTo(5);
        assertThat(actionStatusCount(accepted.id(), "CREATING")).isEqualTo(3);
        verify(messagingClient, times(5)).createAttachment(eq(messageId), any(), eq(30L));
        verify(messagingClient, times(3)).attachment(any(), eq(30L));
    }

    @Test
    void shouldMoveReconciledRunBehindOtherActiveRun() {
        service.createRule(new CreateAutomationRuleRequest(31L, "fair_run_rotation", ChannelType.EMAIL,
                "thread-31", "owner31@example.com", "", "HTTP_ASSET", 1, 100, true));
        UUID firstMessageId = UUID.randomUUID();
        UUID secondMessageId = UUID.randomUUID();
        Instant receivedAt = Instant.now();
        when(messagingClient.get(firstMessageId)).thenReturn(new InboundMessage(firstMessageId, 31L,
                ChannelType.EMAIL, "external-first-31", "thread-31", "owner31@example.com", null,
                "https://files.example/first-31", receivedAt, receivedAt));
        when(messagingClient.get(secondMessageId)).thenReturn(new InboundMessage(secondMessageId, 31L,
                ChannelType.EMAIL, "external-second-31", "thread-31", "owner31@example.com", null,
                "https://files.example/second-31", receivedAt, receivedAt));
        when(downloadClient.create(any(), eq(31L), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> UUID.randomUUID().toString());
        var first = service.process(firstMessageId);
        var second = service.process(secondMessageId);
        jdbcTemplate.update("UPDATE automation_run SET updated_at = ? WHERE id = ?",
                Timestamp.from(receivedAt.minusSeconds(60)), first.id().toString());
        jdbcTemplate.update("UPDATE automation_run SET updated_at = ? WHERE id = ?",
                Timestamp.from(receivedAt.minusSeconds(30)), second.id().toString());

        AutomationRepository.RunClaim firstClaim = repository.claimActiveRuns(1, Duration.ofSeconds(30))
                .getFirst();
        assertThat(firstClaim.messageId()).isEqualTo(firstMessageId);
        service.reconcileClaimed(firstClaim);
        repository.releaseRunClaim(firstClaim);

        AutomationRepository.RunClaim secondClaim = repository.claimActiveRuns(1, Duration.ofSeconds(30))
                .getFirst();
        assertThat(secondClaim.messageId()).isEqualTo(secondMessageId);
        repository.releaseRunClaim(secondClaim);
    }

    @Test
    void shouldAdvanceSecondActionWhenFirstActionPollingKeepsFailing() {
        service.createRule(new CreateAutomationRuleRequest(32L, "fair_action_polling",
                ChannelType.TELEGRAM, "chat-32", "user-32", "", "MESSAGE_ATTACHMENT", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID firstPartId = UUID.randomUUID();
        UUID secondPartId = UUID.randomUUID();
        UUID firstJobId = UUID.randomUUID();
        UUID secondJobId = UUID.randomUUID();
        List<InboundMessage.MessagePart> parts = List.of(
                new InboundMessage.MessagePart(firstPartId, 0, "ATTACHMENT", "IMAGE",
                        "first.png", "image/png", 100L),
                new InboundMessage.MessagePart(secondPartId, 1, "ATTACHMENT", "IMAGE",
                        "second.png", "image/png", 100L));
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 32L,
                ChannelType.TELEGRAM, "external-32", "chat-32", "user-32", null,
                "", Instant.now(), Instant.now(), parts));
        when(messagingClient.createAttachment(messageId, firstPartId, 32L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(firstJobId, "QUEUED"));
        when(messagingClient.createAttachment(messageId, secondPartId, 32L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(secondJobId, "QUEUED"));
        when(messagingClient.attachment(firstJobId, 32L))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(messagingClient.attachment(secondJobId, 32L))
                .thenReturn(new MessagingClient.AttachmentSnapshot(secondJobId, "SUCCEEDED"));

        var accepted = service.process(messageId);
        reconcile(messageId);
        var advanced = reconcile(messageId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT next_attempt_at FROM automation_action
                WHERE automation_run_id = ? AND sequence_number = 0
                """, Timestamp.class, accepted.id().toString())).isNotNull();

        assertThat(advanced.actions()).extracting("status").containsExactly("RUNNING", "SUCCEEDED");
        verify(messagingClient).attachment(secondJobId, 32L);
        verify(messagingClient).attachment(firstJobId, 32L);
    }

    @Test
    void shouldPollFourSubmittedActionsInOneBoundedRunRound() {
        service.createRule(new CreateAutomationRuleRequest(38L, "bounded_submitted_polling",
                ChannelType.TELEGRAM, "chat-38", "user-38", "", "MESSAGE_ATTACHMENT", 4, 100, true));
        UUID messageId = UUID.randomUUID();
        java.util.Map<UUID, UUID> jobsByPart = new java.util.HashMap<>();
        List<InboundMessage.MessagePart> parts = IntStream.range(0, 4).mapToObj(index -> {
            UUID partId = UUID.randomUUID();
            jobsByPart.put(partId, UUID.randomUUID());
            return new InboundMessage.MessagePart(partId, index, "ATTACHMENT", "IMAGE",
                    "image-" + index + ".png", "image/png", 100L);
        }).toList();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 38L,
                ChannelType.TELEGRAM, "external-38", "chat-38", "user-38", null,
                "", Instant.now(), Instant.now(), parts));
        when(messagingClient.createAttachment(eq(messageId), any(UUID.class), eq(38L)))
                .thenAnswer(invocation -> {
                    UUID jobId = jobsByPart.get(invocation.getArgument(1));
                    return new MessagingClient.AttachmentSnapshot(jobId, "QUEUED");
                });
        java.util.concurrent.atomic.AtomicInteger activePolls =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peakPolls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(messagingClient.attachment(any(UUID.class), eq(38L))).thenAnswer(invocation -> {
            int active = activePolls.incrementAndGet();
            peakPolls.accumulateAndGet(active, Math::max);
            try {
                UUID jobId = invocation.getArgument(0);
                return new MessagingClient.AttachmentSnapshot(jobId, "SUCCEEDED");
            } finally {
                activePolls.decrementAndGet();
            }
        });

        AutomationRunView accepted = service.process(messageId);
        reconcile(messageId);
        AutomationRunView advanced = reconcile(messageId);

        assertThat(advanced.status()).isEqualTo("SUCCEEDED");
        assertThat(advanced.actions()).filteredOn(action -> "SUCCEEDED".equals(action.status()))
                .hasSize(4);
        assertThat(peakPolls).hasValue(1);
        verify(messagingClient, times(4)).attachment(any(UUID.class), eq(38L));
        verify(messagingClient, times(4)).createAttachment(
                eq(messageId), any(UUID.class), eq(38L));
        assertThat(accepted.actionCount()).isEqualTo(4);
    }

    @Test
    void shouldFailActionImmediatelyWhenStatusPollReturnsPermanentClientError() {
        UUID messageId = prepareSingleDownload(33L, "permanent_poll", "thread-33");
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 33L))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        service.process(messageId);
        reconcile(messageId);
        var failed = reconcile(messageId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.actions()).extracting("errorCode").containsExactly("AUTOMATION_006");
        assertThat(pollFailureAttempts(messageId)).isEqualTo(1);
    }

    @Test
    void shouldFailActionImmediatelyWhenStatusProtocolIsInvalid() {
        UUID messageId = prepareSingleDownload(34L, "protocol_poll", "thread-34");
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 34L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "UNKNOWN"));

        service.process(messageId);
        reconcile(messageId);
        var failed = reconcile(messageId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(pollFailureAttempts(messageId)).isEqualTo(1);
    }

    @Test
    void shouldExhaustTransientStatusPollFailureBudget() {
        UUID messageId = prepareSingleDownload(35L, "exhausted_poll", "thread-35");
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 35L))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        service.process(messageId);
        reconcile(messageId);
        AutomationRunView run = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            makeActionsDue(messageId);
            run = reconcile(messageId);
            if (attempt < 8) {
                assertThat(run.status()).isEqualTo("RUNNING");
            }
        }

        assertThat(run).isNotNull();
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(pollFailureAttempts(messageId)).isEqualTo(8);
        verify(downloadClient, times(8)).get(downloadId, 35L);
    }

    @Test
    void shouldResetTransientStatusPollFailuresAfterRecovery() {
        UUID messageId = prepareSingleDownload(36L, "recovered_poll", "thread-36");
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 36L))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "SUCCEEDED"));

        service.process(messageId);
        reconcile(messageId);
        reconcile(messageId);
        assertThat(pollFailureAttempts(messageId)).isEqualTo(1);

        makeActionsDue(messageId);
        var recovered = reconcile(messageId);

        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(pollFailureAttempts(messageId)).isZero();
    }

    @Test
    void shouldNotConsumeStatusPollBudgetWhenProgressRelayFails() {
        UUID messageId = prepareSingleDownload(37L, "progress_failure", "thread-37");
        UUID downloadId = UUID.randomUUID();
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 37L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "RUNNING"));
        when(downloadClient.summary(downloadId)).thenThrow(new IllegalStateException("summary unavailable"));

        service.process(messageId);
        reconcile(messageId);
        AutomationRunView running = null;
        for (int attempt = 0; attempt < 9; attempt++) {
            running = reconcile(messageId);
        }

        assertThat(running).isNotNull();
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(pollFailureAttempts(messageId)).isZero();
        verify(downloadClient, times(9)).get(downloadId, 37L);
    }

    @Test
    void shouldSplitUrlBatchesBeforeSerializedPayloadExceedsColumnLimit() {
        service.createRule(new CreateAutomationRuleRequest(24L, "atomic_plan", ChannelType.EMAIL,
                "thread-24", "owner24@example.com", "", "HTTP_ASSET", 2, 100, true));
        UUID messageId = UUID.randomUUID();
        String first = "https://files.example/" + "a".repeat(2100);
        String second = "https://files.example/" + "b".repeat(2100);
        Instant receivedAt = Instant.now();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 24L,
                ChannelType.EMAIL, "external-24", "thread-24", "owner24@example.com", null,
                first + " " + second, receivedAt, receivedAt));

        var accepted = service.process(messageId);

        assertThat(accepted.status()).isEqualTo("RUNNING");
        assertThat(accepted.actionCount()).isEqualTo(2);
        assertThat(accepted.actions()).extracting("actionType")
                .containsExactly("DOWNLOAD_REQUEST", "DOWNLOAD_REQUEST");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_run WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message_link WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT MAX(CHAR_LENGTH(source_url)) FROM automation_action
                WHERE automation_run_id = ?
                """, Integer.class, accepted.id().toString())).isLessThanOrEqualTo(4096);
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void shouldPersistOversizedSingleUrlAsTerminalFailedAction() {
        service.createRule(new CreateAutomationRuleRequest(29L, "oversized_url", ChannelType.EMAIL,
                "thread-29", "owner29@example.com", "", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        String oversized = "https://files.example/" + "x".repeat(4200);
        Instant receivedAt = Instant.now();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 29L,
                ChannelType.EMAIL, "external-29", "thread-29", "owner29@example.com", null,
                oversized, receivedAt, receivedAt));

        var failed = service.process(messageId);

        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("AUTOMATION_005");
        assertThat(failed.actionCount()).isEqualTo(1);
        assertThat(failed.actions()).extracting("status").containsExactly("FAILED");
        assertThat(failed.actions()).extracting("errorCode").containsExactly("AUTOMATION_005");
        String persistedSource = jdbcTemplate.queryForObject("""
                SELECT source_url FROM automation_action WHERE automation_run_id = ?
                """, String.class, failed.id().toString());
        assertThat(persistedSource).startsWith("oversized-url:").hasSizeLessThanOrEqualTo(4096);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_message_link WHERE inbound_message_id = ?",
                Integer.class, messageId.toString())).isEqualTo(1);
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void shouldTerminateHistoricalRunningRunWithoutActions() {
        var rule = service.createRule(new CreateAutomationRuleRequest(25L, "zero_action_recovery",
                ChannelType.EMAIL, "thread-25", "owner25@example.com", "", "HTTP_ASSET",
                1, 100, true));
        UUID messageId = UUID.randomUUID();
        Instant receivedAt = Instant.now();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 25L,
                ChannelType.EMAIL, "external-25", "thread-25", "owner25@example.com", null,
                "https://files.example/recovery", receivedAt, receivedAt));
        repository.beginRun(messageId, rule);

        var duplicate = service.process(messageId);

        assertThat(duplicate.status()).isEqualTo("RUNNING");
        verify(messagingClient, never()).get(messageId);

        var completed = reconcile(messageId);

        assertThat(completed.status()).isEqualTo("FAILED");
        assertThat(completed.errorCode()).isEqualTo("AUTOMATION_002");
        assertThat(completed.actionCount()).isZero();
    }

    @Test
    void shouldNormalizeBareDomainLinksFromForwardedQqMessages() {
        service.createRule(new CreateAutomationRuleRequest(21L, "onebot_bare_link", ChannelType.ONEBOT,
                null, null, "", "HTTP_ASSET", 5, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 21L,
                ChannelType.ONEBOT, "onebot-message", "qq:private:740578608", "740578608", null,
                "pd.qq.com/s/cqrk0y9bw", Instant.now(), Instant.now()));
        when(downloadClient.create(eq(messageId), eq(21L), any(), eq(0), eq("HTTP_ASSET"),
                eq("https://pd.qq.com/s/cqrk0y9bw"), anyString(), any()))
                .thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 21L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));

        service.process(messageId);
        reconcile(messageId);
        var completed = reconcile(messageId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.actionCount()).isEqualTo(1);
        verify(downloadClient).create(eq(messageId), eq(21L), any(), eq(0), eq("HTTP_ASSET"),
                eq("https://pd.qq.com/s/cqrk0y9bw"), anyString(), any());
    }

    @Test
    void shouldPersistSafeRunScopedFeedbackWithoutDownloadingDuplicateLink() {
        service.createRule(new CreateAutomationRuleRequest(20L, "deduplicate_links", ChannelType.EMAIL,
                "thread-20", "owner@example.test", "", "HTTP_ASSET", 1, 100, true));
        UUID firstMessageId = UUID.randomUUID();
        UUID duplicateMessageId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-08-28T01:00:00Z");
        when(messagingClient.get(firstMessageId)).thenReturn(new InboundMessage(firstMessageId, 20L,
                ChannelType.EMAIL, "external-first", "thread-20", "owner@example.test", null,
                "https://mobile.x.com/example/status/123/photo/1", receivedAt, receivedAt));
        when(messagingClient.get(duplicateMessageId)).thenReturn(new InboundMessage(duplicateMessageId, 20L,
                ChannelType.EMAIL, "external-duplicate", "thread-20", "owner@example.test", null,
                "https://x.com/i/web/status/123", receivedAt.plusSeconds(3600), receivedAt.plusSeconds(3600)));
        UUID requestId = UUID.randomUUID();
        when(downloadClient.create(eq(firstMessageId), eq(20L), any(), eq(0), eq("HTTP_ASSET"),
                eq("https://x.com/i/web/status/123"), anyString(), eq(receivedAt)))
                .thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 20L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));

        service.process(firstMessageId);
        reconcile(firstMessageId);
        var first = reconcile(firstMessageId);
        var duplicate = service.process(duplicateMessageId);

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(duplicate.status()).isEqualTo("SUCCEEDED");
        assertThat(duplicate.actionCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_outbox WHERE aggregate_id = ?",
                Integer.class, duplicate.id().toString())).isOne();
        var feedbackEvent = jdbcTemplate.queryForMap("""
                SELECT event_type, payload_json, published_at FROM automation_outbox
                WHERE aggregate_id = ?
                """, duplicate.id().toString());
        assertThat(feedbackEvent.get("event_type")).isEqualTo("AutomationDuplicateLinksDetected");
        assertThat(storedText(feedbackEvent.get("payload_json")))
                .doesNotContain("https://x.com/i/web/status/123");
        assertThat(feedbackEvent.get("published_at")).isNotNull();
        verify(downloadClient, times(1)).create(any(), anyLong(), any(), anyInt(), anyString(),
                anyString(), anyString(), any());
        verify(messagingClient).reply(duplicateMessageId,
                "automation-duplicate-links-" + duplicate.id(), DuplicateLinkFeedback.body());
    }

    @Test
    void shouldMergeManyDuplicateLinkRepliesIntoOneMessage() {
        service.createRule(new CreateAutomationRuleRequest(26L, "merge_duplicate_links", ChannelType.EMAIL,
                "thread-26", "owner26@example.test", "", "HTTP_ASSET", 5, 100, true));
        UUID firstMessageId = UUID.randomUUID();
        UUID duplicateMessageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-08-28T01:00:00Z");
        List<String> urls = IntStream.range(0, 5)
                .mapToObj(index -> "https://files.example/duplicate-" + index).toList();
        String body = String.join(" ", urls);
        when(messagingClient.get(firstMessageId)).thenReturn(new InboundMessage(firstMessageId, 26L,
                ChannelType.EMAIL, "external-first-26", "thread-26", "owner26@example.test", null,
                body, receivedAt, receivedAt));
        when(messagingClient.get(duplicateMessageId)).thenReturn(new InboundMessage(duplicateMessageId, 26L,
                ChannelType.EMAIL, "external-duplicate-26", "thread-26", "owner26@example.test", null,
                body, receivedAt.plusSeconds(3600), receivedAt.plusSeconds(3600)));
        when(downloadClient.createBatch(eq(firstMessageId), eq(26L), any(), eq(0), eq(urls),
                eq(receivedAt), eq(body))).thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 26L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));
        service.process(firstMessageId);
        reconcile(firstMessageId);
        reconcile(firstMessageId);
        clearInvocations(messagingClient, downloadClient);

        var duplicate = service.process(duplicateMessageId);

        assertThat(duplicate.status()).isEqualTo("SUCCEEDED");
        verify(messagingClient).reply(duplicateMessageId,
                "automation-duplicate-links-" + duplicate.id(), DuplicateLinkFeedback.body());
        verify(messagingClient, times(1)).reply(eq(duplicateMessageId), anyString(), anyString());
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
        verify(downloadClient, never()).createBatch(any(), anyLong(), any(), anyInt(), any(), any(), anyString());
    }

    @Test
    void shouldReliablyRelayDuplicateFeedbackAfterSynchronousFailureAndRedelivery() {
        service.createRule(new CreateAutomationRuleRequest(35L, "durable_duplicate_feedback",
                ChannelType.EMAIL, "thread-35", "owner35@example.test", "", "HTTP_ASSET",
                1, 100, true));
        UUID firstMessageId = UUID.randomUUID();
        UUID duplicateMessageId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-08-28T01:00:00Z");
        String sourceUrl = "https://files.example/private-item";
        when(messagingClient.get(firstMessageId)).thenReturn(new InboundMessage(firstMessageId, 35L,
                ChannelType.EMAIL, "external-first-35", "thread-35", "owner35@example.test", null,
                sourceUrl, receivedAt, receivedAt));
        when(messagingClient.get(duplicateMessageId)).thenReturn(new InboundMessage(duplicateMessageId, 35L,
                ChannelType.EMAIL, "external-duplicate-35", "thread-35", "owner35@example.test", null,
                sourceUrl, receivedAt.plusSeconds(3600), receivedAt.plusSeconds(3600)));
        when(downloadClient.create(eq(firstMessageId), eq(35L), any(), eq(0), eq("HTTP_ASSET"),
                eq(sourceUrl), anyString(), eq(receivedAt))).thenReturn(requestId.toString());
        when(downloadClient.get(requestId, 35L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(requestId, "SUCCEEDED"));
        service.process(firstMessageId);
        reconcile(firstMessageId);
        AutomationRunView first = reconcile(firstMessageId);
        jdbcTemplate.update("UPDATE automation_outbox SET published_at = ? WHERE aggregate_id = ?",
                Timestamp.from(Instant.now()), first.id().toString());
        clearInvocations(messagingClient, downloadClient);
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary"))
                .doReturn(new MessagingClient.InboundReplySnapshot(
                        duplicateMessageId, "EMAIL", "ACCEPTED"))
                .when(messagingClient).reply(eq(duplicateMessageId),
                        startsWith("automation-duplicate-links-"), eq(DuplicateLinkFeedback.body()));

        AutomationRunView duplicate = service.process(duplicateMessageId);
        AutomationRunView replayed = service.process(duplicateMessageId);

        assertThat(replayed.id()).isEqualTo(duplicate.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ? AND published_at IS NULL AND dead_at IS NULL
                """, Integer.class, duplicate.id().toString())).isOne();
        verify(messagingClient, times(1)).get(duplicateMessageId);
        verify(messagingClient, times(1)).reply(duplicateMessageId,
                "automation-duplicate-links-" + duplicate.id(), DuplicateLinkFeedback.body());

        // 共享测试库中的其他用例事件与本场景无关，避免它们占满四条领取窗口。
        jdbcTemplate.update("""
                UPDATE automation_outbox SET published_at = ?
                WHERE aggregate_id <> ? AND published_at IS NULL
                """, Timestamp.from(Instant.now()), duplicate.id().toString());
        AutomationProperties properties = new AutomationProperties(
                "internal", "http://messaging.test", "messaging-token",
                "http://download.test", "download-token", true, 10, 100, 3, 25);
        new CompletionOutboxRelay(repository, properties, messagingClient, downloadClient).relay();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ? AND published_at IS NOT NULL
                """, Integer.class, duplicate.id().toString())).isOne();
        verify(messagingClient, times(2)).reply(duplicateMessageId,
                "automation-duplicate-links-" + duplicate.id(), DuplicateLinkFeedback.body());
        verify(messagingClient, times(1)).get(duplicateMessageId);
    }

    @Test
    void shouldSelfHealLegacyDuplicateOnlyRunOnlyWhenItIsRedelivered() {
        var rule = service.createRule(new CreateAutomationRuleRequest(36L,
                "legacy_duplicate_feedback", ChannelType.QQ, "qq:private:36", "36", "",
                "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO automation_run
                    (id, inbound_message_id, automation_rule_id, rule_version, status, action_count,
                     action_refs_json, error_code, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'SUCCEEDED', 0, '[]', NULL, ?, ?)
                """, runId.toString(), messageId.toString(), rule.id().toString(),
                Timestamp.from(now), Timestamp.from(now));
        when(messagingClient.reply(messageId, "automation-duplicate-links-" + runId,
                DuplicateLinkFeedback.body())).thenReturn(
                new MessagingClient.InboundReplySnapshot(messageId, "QQ", "ACCEPTED"));

        AutomationRunView repaired = service.process(messageId);
        AutomationRunView replayed = service.process(messageId);

        assertThat(repaired.id()).isEqualTo(runId);
        assertThat(replayed.id()).isEqualTo(runId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_outbox
                WHERE aggregate_id = ? AND event_type = 'AutomationDuplicateLinksDetected'
                """, Integer.class, runId.toString())).isOne();
        verify(messagingClient, never()).get(messageId);
        verify(messagingClient, times(1)).reply(messageId,
                "automation-duplicate-links-" + runId, DuplicateLinkFeedback.body());
    }

    @Test
    void shouldFailCreatingActionOnlyAfterTenSubmissionAttempts() {
        service.createRule(new CreateAutomationRuleRequest(27L, "bounded_submission_attempts", ChannelType.EMAIL,
                "thread-27", "owner27@example.test", "", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        Instant receivedAt = Instant.now();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, 27L,
                ChannelType.EMAIL, "external-27", "thread-27", "owner27@example.test", null,
                "https://files.example/retry", receivedAt, receivedAt));
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("transient"));

        service.process(messageId);
        long retryWindowSeconds = 0;
        AutomationRunView failed = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            failed = reconcile(messageId);
            RetryState retryState = retryState(messageId);
            assertThat(retryState.attempts()).isEqualTo(attempt);
            if (attempt < 10) {
                assertThat(retryState.nextAttemptAt()).isNotNull();
                retryWindowSeconds += Duration.between(
                        retryState.updatedAt(), retryState.nextAttemptAt()).toSeconds();
                if (attempt == 1) {
                    reconcile(messageId);
                    assertThat(retryState(messageId).attempts()).isEqualTo(1);
                }
                makeCreatingActionsDue(messageId);
            }
        }

        assertThat(failed).isNotNull();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.actions()).extracting("status").containsExactly("FAILED");
        assertThat(retryWindowSeconds).isGreaterThanOrEqualTo(240);
        verify(downloadClient, times(10)).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());
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
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("unknown result")).thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 14L)).thenReturn(
                new DownloadIngestionClient.DownloadSnapshot(downloadId, "SUCCEEDED"));

        var accepted = service.process(messageId);

        assertThat(accepted.actions()).extracting("status").containsExactly("CREATING");
        verify(downloadClient, never()).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(),
                anyString(), any());

        var uncertain = reconcile(messageId);
        makeCreatingActionsDue(messageId);
        var recovered = reconcile(messageId);
        var reconciled = reconcile(messageId);

        assertThat(uncertain.status()).isEqualTo("RUNNING");
        assertThat(recovered.status()).isEqualTo("RUNNING");
        assertThat(reconciled.status()).isEqualTo("SUCCEEDED");
        assertThat(reconciled.actions()).extracting("externalRequestId").containsExactly(downloadId);
        verify(downloadClient, times(2)).create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldRelayOnlyLatestQuarterMilestoneForQq() {
        service.createRule(new CreateAutomationRuleRequest(18L, "progress_download", ChannelType.QQ,
                null, "qq-progress-user", "", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        InboundMessage inbound = new InboundMessage(messageId, 18L, ChannelType.QQ,
                "qq_main:C2C_MESSAGE_CREATE:progress", "qq:c2c:qq-progress-user", "qq-progress-user", null,
                "https://files.example/large.zip", Instant.now(), Instant.now());
        when(messagingClient.get(messageId)).thenReturn(inbound);
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 18L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "RUNNING"));
        when(downloadClient.summary(downloadId))
                .thenReturn(new DownloadIngestionClient.DownloadSummary(
                        downloadId, "RUNNING", 63, 7_927_234L, 12_582_912L, List.of()))
                .thenReturn(new DownloadIngestionClient.DownloadSummary(
                        downloadId, "RUNNING", 90, 11_324_621L, 12_582_912L, List.of()))
                .thenReturn(new DownloadIngestionClient.DownloadSummary(
                        downloadId, "RUNNING", 99, 12_457_083L, 12_582_912L, List.of()));

        service.process(messageId);
        reconcile(messageId);
        reconcile(messageId);
        reconcile(messageId);
        var running = reconcile(messageId);

        assertThat(running.status()).isEqualTo("RUNNING");
        verify(messagingClient).reply(eq(messageId), anyString(), eq("下载进度：50%（6.0/12.0 MiB）。"));
        verify(messagingClient).reply(eq(messageId), anyString(), eq("下载进度：75%（9.0/12.0 MiB）。"));
        verify(messagingClient, times(2)).reply(eq(messageId), startsWith("automation-progress-"), anyString());
    }

    @Test
    void shouldKeepFivePercentMilestoneForOtherChannels() {
        service.createRule(new CreateAutomationRuleRequest(28L, "email_progress_download", ChannelType.EMAIL,
                "thread-28", "owner28@example.test", "", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        InboundMessage inbound = new InboundMessage(messageId, 28L, ChannelType.EMAIL,
                "external-28", "thread-28", "owner28@example.test", null,
                "https://files.example/email-large.zip", Instant.now(), Instant.now());
        when(messagingClient.get(messageId)).thenReturn(inbound);
        when(downloadClient.create(any(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(downloadId.toString());
        when(downloadClient.get(downloadId, 28L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadId, "RUNNING"));
        when(downloadClient.summary(downloadId)).thenReturn(new DownloadIngestionClient.DownloadSummary(
                downloadId, "RUNNING", 15, 1_887_437L, 12_582_912L, List.of()));

        service.process(messageId);
        reconcile(messageId);
        var running = reconcile(messageId);

        assertThat(running.status()).isEqualTo("RUNNING");
        verify(messagingClient).reply(eq(messageId), anyString(), eq("下载进度：15%（1.8/12.0 MiB）。"));
    }

    private InboundMessage message(UUID id, long ownerId, String conversation, String sender, String body) {
        Instant now = Instant.now();
        return new InboundMessage(id, ownerId, ownerId == 11L ? ChannelType.TELEGRAM : ChannelType.EMAIL,
                "external-" + id, conversation, sender, null, body, now, now);
    }

    private AutomationRunView reconcile(UUID messageId) {
        // 测试主动推进正常轮询时跳过生产环境的短暂节流；失败退避预算保持原样。
        jdbcTemplate.update("""
                UPDATE automation_action SET next_attempt_at = ?
                WHERE automation_run_id = (SELECT id FROM automation_run WHERE inbound_message_id = ?)
                  AND status = 'RUNNING' AND poll_failure_attempts = 0
                """, Timestamp.from(Instant.EPOCH), messageId.toString());
        var claim = repository.claimRun(messageId, Duration.ofSeconds(30));
        if (claim.isEmpty()) {
            return service.get(messageId);
        }
        try {
            return service.reconcileClaimed(claim.get());
        } finally {
            repository.releaseRunClaim(claim.get());
        }
    }

    private UUID prepareSingleDownload(long ownerId, String ruleName, String conversation) {
        service.createRule(new CreateAutomationRuleRequest(ownerId, ruleName, ChannelType.EMAIL,
                conversation, "owner" + ownerId + "@example.test", "", "HTTP_ASSET", 1, 100, true));
        UUID messageId = UUID.randomUUID();
        Instant now = Instant.now();
        when(messagingClient.get(messageId)).thenReturn(new InboundMessage(messageId, ownerId, ChannelType.EMAIL,
                "external-" + messageId, conversation, "owner" + ownerId + "@example.test", null,
                "https://files.example/" + ownerId + ".zip", now, now));
        return messageId;
    }

    private void makeActionsDue(UUID messageId) {
        jdbcTemplate.update("""
                UPDATE automation_action SET next_attempt_at = ?
                WHERE automation_run_id = (SELECT id FROM automation_run WHERE inbound_message_id = ?)
                """, Timestamp.from(Instant.EPOCH), messageId.toString());
    }

    private int pollFailureAttempts(UUID messageId) {
        Integer attempts = jdbcTemplate.queryForObject("""
                SELECT poll_failure_attempts FROM automation_action
                WHERE automation_run_id = (SELECT id FROM automation_run WHERE inbound_message_id = ?)
                """, Integer.class, messageId.toString());
        return attempts == null ? 0 : attempts;
    }

    private void makeCreatingActionsDue(UUID messageId) {
        jdbcTemplate.update("""
                UPDATE automation_action SET next_attempt_at = ?
                WHERE automation_run_id = (SELECT id FROM automation_run WHERE inbound_message_id = ?)
                  AND status = 'CREATING'
                """, Timestamp.from(Instant.EPOCH), messageId.toString());
    }

    private RetryState retryState(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                SELECT submission_attempts, next_attempt_at, updated_at FROM automation_action
                WHERE automation_run_id = (SELECT id FROM automation_run WHERE inbound_message_id = ?)
                """, (resultSet, rowNumber) -> {
            Timestamp nextAttemptAt = resultSet.getTimestamp("next_attempt_at");
            return new RetryState(resultSet.getInt("submission_attempts"),
                    nextAttemptAt == null ? null : nextAttemptAt.toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant());
        }, messageId.toString());
    }

    private int actionStatusCount(UUID runId, String status) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM automation_action WHERE automation_run_id = ? AND status = ?
                """, Integer.class, runId.toString(), status);
        return count == null ? 0 : count;
    }

    private String storedText(Object value) {
        return value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    private record RetryState(int attempts, Instant nextAttemptAt, Instant updatedAt) {
    }
}
