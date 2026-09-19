package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.OneBotInboundRequest;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * OneBot 入站适配器集成测试。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:messaging_onebot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "messaging.onebot-forward-relay-delay-ms=3600000",
        "messaging.onebot-acceptance-relay-delay-ms=3600000"
})
class OneBotInboundAdapterTest {

    @Autowired
    private OneBotInboundAdapter adapter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AttachmentDownloadService attachmentDownloadService;

    @MockBean
    private TaskSchedulerClient schedulerClient;

    @MockBean
    private DownloadIngestionClient downloadIngestionClient;

    @MockBean
    private ProviderFileResolverClient providerFileResolverClient;

    @MockBean
    private OneBotForwardExpansionClient forwardExpansionClient;

    @Autowired
    private OneBotForwardExpansionRelay forwardExpansionRelay;

    @Autowired
    private MessagingRepository messagingRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void shouldNormalizeAttachmentsAndDeduplicateEvent() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type": "message",
                  "message_type": "group",
                  "self_id": 90001,
                  "message_id": 42,
                  "group_id": 20002,
                  "user_id": 10001,
                  "time": 1710000000,
                  "message": [
                    {"type":"text","data":{"text":"hello"}},
                    {"type":"image","data":{"file":"opaque.jpg","url":"https://cdn.example.test/a.jpg","file_size":"123"}},
                    {"type":"file","data":{"file_id":"book-1","name":"book.txt","size":456}}
                  ]
                }
                """);

        var first = adapter.receive(new OneBotInboundRequest(9L, "napcat-main", event));
        var replay = adapter.receive(new OneBotInboundRequest(9L, "napcat-main", event));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.preAcknowledged()).isFalse();
        assertThat(first.body()).isEqualTo("hello");
        assertThat(first.parts()).hasSize(3);
        assertThat(first.parts()).extracting("type").containsExactly("TEXT", "ATTACHMENT", "ATTACHMENT");
        var image = first.parts().stream().filter(part -> "opaque.jpg".equals(part.providerFileId()))
                .findFirst().orElseThrow();
        assertThat(image.declaredSize()).isEqualTo(123L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inbound_message WHERE owner_id = 9", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inbound_message_part p
                JOIN inbound_message m ON m.id = p.inbound_message_id WHERE m.owner_id = 9
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void shouldRemoveCqAttachmentFromFallbackText() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":43, "user_id":10002,
                  "raw_message":"note[CQ:image,file=a.jpg,url=https://cdn.example.test/a.jpg]",
                  "message":[{"type":"image","data":{"file":"a.jpg","url":"https://cdn.example.test/a.jpg"}}]
                }
                """);

        var result = adapter.receive(new OneBotInboundRequest(10L, "napcat-main", event));

        assertThat(result.body()).isEqualTo("note");
        assertThat(result.parts()).extracting("type").containsExactly("ATTACHMENT", "TEXT");
    }

    @Test
    void shouldDeduplicateAttachmentRepeatedAcrossForwardStructures() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":45, "user_id":10002,
                  "message":[
                    {"type":"image","data":{"file_id":"same-1","url":"https://cdn.example.test/a.jpg"}},
                    {"type":"image","data":{"file_id":"same-1","url":"https://cdn.example.test/a.jpg"}}
                  ]
                }
                """);

        var result = adapter.receive(new OneBotInboundRequest(10L, "napcat-main", event));

        assertThat(result.parts()).hasSize(1);
        assertThat(result.parts().getFirst().providerFileId()).isEqualTo("same-1");
    }

    @Test
    void shouldPersistPureForwardBeforeSlowExpansionAndPublishOnlyAfterCompletion() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":148, "user_id":10148,
                  "message":[{"type":"forward","data":{"id":"forward-slow-148"}}]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-slow-148")).thenAnswer(invocation -> {
            Thread.sleep(1_100L);
            return objectMapper.readTree("""
                    [{"type":"image","data":{"file_id":"expanded-148","name":"expanded.png"}}]
                    """);
        });

        var message = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> adapter.receive(new OneBotInboundRequest(148L, "napcat-main", event)));

        assertThat(message.body()).isEqualTo("[forward]");
        assertThat(message.preAcknowledged()).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onebot_inbound_processing
                WHERE inbound_message_id = ? AND status = 'PENDING'
                """, Integer.class, message.id().toString())).isEqualTo(1);
        assertThat(receivedOutboxCount(message.id())).isZero();
        assertThat(acceptanceOutboxCount(message.id())).isEqualTo(1);
        var acceptanceEvent = messagingRepository.claimUnpublishedOneBotAcceptanceEvents(
                        200, Duration.ofSeconds(30)).stream()
                .filter(eventItem -> eventItem.messageId().equals(message.id())).findFirst().orElseThrow();
        assertThat(acceptanceEvent.valid()).isTrue();
        assertThat(acceptanceEvent.idempotencyKey()).isEqualTo("onebot-forward-accepted:" + message.id());
        verify(forwardExpansionClient, never()).expand(anyString(), anyString());

        assertThat(messagingRepository.markOutboxPublished(
                acceptanceEvent.id(), acceptanceEvent.claimToken())).isTrue();

        assertThat(acceptanceOutboxCount(message.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT published_at IS NOT NULL FROM messaging_outbox
                WHERE aggregate_id = ? AND event_type = 'OneBotForwardAccepted'
                """, Boolean.class, message.id().toString())).isTrue();
        forwardExpansionRelay.relay();

        var completed = adapter.receive(new OneBotInboundRequest(148L, "napcat-main", event));
        assertThat(completed.id()).isEqualTo(message.id());
        assertThat(completed.preAcknowledged()).isTrue();
        assertThat(completed.parts()).extracting("providerFileId").contains("expanded-148");
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        verify(forwardExpansionClient, times(1)).expand("napcat-main", "forward-slow-148");
    }

    @Test
    void shouldRetryTransientForwardFailureWithoutPublishingIncompleteMessage() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":149, "user_id":10149,
                  "message":[{"type":"forward","data":{"id":"forward-retry-149"}}]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-retry-149"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"))
                .thenReturn(objectMapper.readTree("""
                        [{"type":"file","data":{"file_id":"expanded-149","name":"expanded.zip"}}]
                        """));
        var message = adapter.receive(new OneBotInboundRequest(149L, "napcat-main", event));

        forwardExpansionRelay.relay();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("PENDING");
        assertThat(receivedOutboxCount(message.id())).isZero();
        jdbcTemplate.update("""
                UPDATE onebot_forward_expansion SET next_attempt_at = CURRENT_TIMESTAMP
                WHERE inbound_message_id = ?
                """, message.id().toString());

        forwardExpansionRelay.relay();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, Integer.class, message.id().toString())).isEqualTo(2);
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        assertThat(adapter.receive(new OneBotInboundRequest(149L, "napcat-main", event)).parts())
                .extracting("providerFileId").contains("expanded-149");
    }

    @Test
    void shouldDeduplicateForwardReplayBeforeAndAfterExpansion() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":150, "user_id":10150,
                  "message":[{"type":"forward","data":{"id":"forward-once-150"}}]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-once-150"))
                .thenReturn(objectMapper.readTree("""
                        [{"type":"image","data":{"file_id":"expanded-150","name":"expanded.jpg"}}]
                        """));

        var first = adapter.receive(new OneBotInboundRequest(150L, "napcat-main", event));
        var replayBeforeExpansion = adapter.receive(new OneBotInboundRequest(150L, "napcat-main", event));
        forwardExpansionRelay.relay();
        var replayAfterExpansion = adapter.receive(new OneBotInboundRequest(150L, "napcat-main", event));
        forwardExpansionRelay.relay();

        assertThat(replayBeforeExpansion.id()).isEqualTo(first.id());
        assertThat(replayAfterExpansion.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, Integer.class, first.id().toString())).isEqualTo(1);
        assertThat(acceptanceOutboxCount(first.id())).isEqualTo(1);
        assertThat(receivedOutboxCount(first.id())).isEqualTo(1);
        verify(forwardExpansionClient, times(1)).expand("napcat-main", "forward-once-150");
    }

    @Test
    void shouldPublishExplicitTerminalResultForPermanentPureForwardFailure() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":151, "user_id":10151,
                  "message":[{"type":"forward","data":{"id":"forward-gone-151"}}]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-gone-151"))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "not found", null, null, null));
        var message = adapter.receive(new OneBotInboundRequest(151L, "napcat-main", event));

        forwardExpansionRelay.relay();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, Integer.class, message.id().toString())).isEqualTo(1);
        var acceptanceEvent = messagingRepository.claimUnpublishedOneBotAcceptanceEvents(
                        200, Duration.ofSeconds(30)).stream()
                .filter(eventItem -> eventItem.messageId().equals(message.id())).findFirst().orElseThrow();
        assertThat(messagingRepository.recordInboundOutboxFailure(
                acceptanceEvent.id(), acceptanceEvent.claimToken(), 1, "ReplyUnavailable"))
                .isEqualTo(MessagingRepository.InboundOutboxFailureOutcome.DEAD);
        var completed = adapter.receive(new OneBotInboundRequest(151L, "napcat-main", event));
        assertThat(completed.preAcknowledged()).isFalse();
        assertThat(completed.parts()).extracting("text").contains("[forward unavailable]");
        var failurePart = completed.parts().stream()
                .filter(part -> "FORWARD_ERROR".equals(part.attachmentType())).findFirst().orElseThrow();
        var failureJob = attachmentDownloadService.create(message.id(), failurePart.id());
        var failureJobReplay = attachmentDownloadService.create(message.id(), failurePart.id());
        assertThat(failureJob.status()).isEqualTo("FAILED");
        assertThat(failureJobReplay.id()).isEqualTo(failureJob.id());
        assertThat(failureJob.taskId()).isNull();
        assertThat(failureJob.downloadRequestId()).isNull();
        assertThat(failureJob.lastErrorCode()).isEqualTo("ONEBOT_FORWARD_UNAVAILABLE");
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        verify(schedulerClient, never()).createAttachmentDownloadTask(any());
        verify(downloadIngestionClient, never()).createHttpAttachment(
                any(), anyLong(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void shouldPublishMixedForwardSuccessAndFailureExactlyOnce() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":152, "user_id":10152,
                  "message":[
                    {"type":"forward","data":{"id":"forward-good-152"}},
                    {"type":"forward","data":{"id":"forward-gone-152"}}
                  ]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-good-152"))
                .thenReturn(objectMapper.readTree("""
                        [{"type":"image","data":{"file_id":"expanded-152","name":"expanded.jpg"}}]
                        """));
        when(forwardExpansionClient.expand("napcat-main", "forward-gone-152"))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.NOT_FOUND, "not found", null, null, null));
        var message = adapter.receive(new OneBotInboundRequest(152L, "napcat-main", event));

        forwardExpansionRelay.relay();
        forwardExpansionRelay.relay();

        var completed = adapter.receive(new OneBotInboundRequest(152L, "napcat-main", event));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("PARTIAL_FAILED");
        assertThat(completed.parts()).extracting("providerFileId").contains("expanded-152");
        assertThat(completed.parts()).extracting("text").contains("[forward unavailable]");
        assertThat(completed.parts()).extracting("attachmentType").contains("FORWARD_ERROR");
        assertThat(completed.parts().stream().filter(part -> "ATTACHMENT".equals(part.type()))
                .map(part -> part.attachmentType()).toList()).containsExactly("FORWARD_ERROR", "IMAGE");
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        verify(forwardExpansionClient, times(1)).expand("napcat-main", "forward-good-152");
        verify(forwardExpansionClient, times(1)).expand("napcat-main", "forward-gone-152");
    }

    @Test
    void shouldRecoverExpiredForwardLeaseAfterRestart() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":153, "user_id":10153,
                  "message":[{"type":"forward","data":{"id":"forward-restart-153"}}]
                }
                """);
        when(forwardExpansionClient.expand("napcat-main", "forward-restart-153"))
                .thenReturn(objectMapper.readTree("""
                        [{"type":"file","data":{"file_id":"expanded-153","name":"expanded.bin"}}]
                        """));
        var message = adapter.receive(new OneBotInboundRequest(153L, "napcat-main", event));
        var staged = messagingRepository.findDueOneBotForwardExpansions(10).stream()
                .filter(job -> job.messageId().equals(message.id())).findFirst().orElseThrow();
        assertThat(messagingRepository.claimOneBotForwardExpansion(
                staged.id(), UUID.randomUUID(), Instant.now().minusSeconds(1))).isTrue();

        forwardExpansionRelay.relay();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, Integer.class, message.id().toString())).isEqualTo(2);
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        assertThat(acceptanceOutboxCount(message.id())).isEqualTo(1);
        verify(forwardExpansionClient, times(1)).expand("napcat-main", "forward-restart-153");
    }

    @Test
    void shouldSurfaceForwardReferenceBudgetTruncationAsVisibleFailure() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":155, "user_id":10155,
                  "message":[
                    {"type":"forward","data":{"id":"forward-1-155"}},
                    {"type":"forward","data":{"id":"forward-2-155"}},
                    {"type":"forward","data":{"id":"forward-3-155"}},
                    {"type":"forward","data":{"id":"forward-4-155"}},
                    {"type":"forward","data":{"id":"forward-5-155"}},
                    {"type":"forward","data":{"id":"forward-6-155"}},
                    {"type":"forward","data":{"id":"forward-7-155"}},
                    {"type":"forward","data":{"id":"forward-8-155"}},
                    {"type":"forward","data":{"id":"forward-9-155"}}
                  ]
                }
                """);
        when(forwardExpansionClient.expand(org.mockito.ArgumentMatchers.eq("napcat-main"), anyString()))
                .thenAnswer(invocation -> objectMapper.readTree("""
                        [{"type":"image","data":{"file_id":"%s","name":"expanded.jpg"}}]
                        """.formatted("expanded-" + invocation.<String>getArgument(1))));
        var message = adapter.receive(new OneBotInboundRequest(155L, "napcat-main", event));

        forwardExpansionRelay.relay();

        var completed = adapter.receive(new OneBotInboundRequest(155L, "napcat-main", event));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM onebot_forward_expansion WHERE inbound_message_id = ?
                """, Integer.class, message.id().toString())).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("PARTIAL_FAILED");
        assertThat(completed.body()).contains("[forward unavailable]");
        assertThat(completed.parts()).extracting("attachmentType").contains("FORWARD_ERROR");
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        verify(forwardExpansionClient, times(8)).expand(
                org.mockito.ArgumentMatchers.eq("napcat-main"), anyString());
    }

    @Test
    void shouldPersistPartBudgetTruncationAndRouteFailureWithoutExpansion() {
        var event = objectMapper.createObjectNode();
        event.put("post_type", "message");
        event.put("message_type", "private");
        event.put("self_id", 90001);
        event.put("message_id", 156);
        event.put("user_id", 10156);
        var segments = event.putArray("message");
        for (int index = 0; index < 501; index++) {
            var data = segments.addObject().put("type", "image").putObject("data");
            data.put("file_id", "part-" + index + "-156");
            data.put("name", "part-" + index + ".png");
        }

        var message = adapter.receive(new OneBotInboundRequest(156L, "napcat-main", event));
        var completed = adapter.receive(new OneBotInboundRequest(156L, "napcat-main", event));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("PARTIAL_FAILED");
        assertThat(completed.body()).contains("[forward unavailable]");
        assertThat(completed.parts().stream()
                .filter(part -> "FORWARD_ERROR".equals(part.attachmentType()))).hasSize(1);
        assertThat(completed.parts().stream()
                .filter(part -> "ATTACHMENT".equals(part.type())
                        && !"FORWARD_ERROR".equals(part.attachmentType()))).hasSize(500);
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        verify(forwardExpansionClient, never()).expand(anyString(), anyString());
    }

    @Test
    void shouldSurfaceCombinedStoredAndExpandedPartBudgetTruncation() throws Exception {
        var event = objectMapper.createObjectNode();
        event.put("post_type", "message");
        event.put("message_type", "private");
        event.put("self_id", 90001);
        event.put("message_id", 157);
        event.put("user_id", 10157);
        var segments = event.putArray("message");
        for (int index = 0; index < 499; index++) {
            var data = segments.addObject().put("type", "image").putObject("data");
            data.put("file_id", "direct-" + index + "-157");
            data.put("name", "direct-" + index + ".png");
        }
        segments.addObject().put("type", "forward").putObject("data").put("id", "forward-157");
        when(forwardExpansionClient.expand("napcat-main", "forward-157"))
                .thenReturn(objectMapper.readTree("""
                        [
                          {"type":"image","data":{"file_id":"expanded-a-157","name":"a.png"}},
                          {"type":"image","data":{"file_id":"expanded-b-157","name":"b.png"}}
                        ]
                        """));

        var message = adapter.receive(new OneBotInboundRequest(157L, "napcat-main", event));
        forwardExpansionRelay.relay();

        var completed = adapter.receive(new OneBotInboundRequest(157L, "napcat-main", event));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM onebot_inbound_processing WHERE inbound_message_id = ?
                """, String.class, message.id().toString())).isEqualTo("PARTIAL_FAILED");
        assertThat(completed.body()).contains("[forward unavailable]");
        assertThat(completed.parts()).extracting("providerFileId").contains("expanded-a-157");
        assertThat(completed.parts()).extracting("providerFileId").doesNotContain("expanded-b-157");
        assertThat(completed.parts().stream()
                .filter(part -> "ATTACHMENT".equals(part.type())
                        && !"FORWARD_ERROR".equals(part.attachmentType()))).hasSize(500);
        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
    }

    @Test
    void shouldSerializeConcurrentLastForwardCompletionsAndPublishOnce() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":154, "user_id":10154,
                  "message":[
                    {"type":"forward","data":{"id":"forward-a-154"}},
                    {"type":"forward","data":{"id":"forward-b-154"}}
                  ]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(154L, "napcat-main", event));
        var jobs = messagingRepository.findDueOneBotForwardExpansions(10).stream()
                .filter(job -> job.messageId().equals(message.id())).toList();
        assertThat(jobs).hasSize(2);
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        assertThat(messagingRepository.claimOneBotForwardExpansion(
                jobs.get(0).id(), firstToken, Instant.now().plusSeconds(30))).isTrue();
        assertThat(messagingRepository.claimOneBotForwardExpansion(
                jobs.get(1).id(), secondToken, Instant.now().plusSeconds(30))).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> completeConcurrently(jobs.get(0), firstToken,
                    "expanded-a-154", ready, start));
            var second = executor.submit(() -> completeConcurrently(jobs.get(1), secondToken,
                    "expanded-b-154", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(receivedOutboxCount(message.id())).isEqualTo(1);
        assertThat(adapter.receive(new OneBotInboundRequest(154L, "napcat-main", event)).parts())
                .extracting("providerFileId").contains("expanded-a-154", "expanded-b-154");
    }

    @Test
    void shouldSubmitAndCancelHttpAttachmentWithoutWrapperTask() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":44, "user_id":10003,
                  "message":[{"type":"image","data":{"file":"a.jpg","url":"https://cdn.example.test/a.jpg"}}]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(11L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any()))
                .thenReturn(downloadRequestId);
        when(downloadIngestionClient.cancel(downloadRequestId, 11L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLED"));

        var job = attachmentDownloadService.create(message.id(), part.id());
        var replay = attachmentDownloadService.create(message.id(), part.id());
        var cancelled = attachmentDownloadService.cancel(job.id(), 11L);
        var alreadyCancelled = attachmentDownloadService.cancel(job.id(), 11L);

        assertThat(replay.id()).isEqualTo(job.id());
        assertThat(job.taskId()).isNull();
        assertThat(job.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(replay.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(alreadyCancelled.status()).isEqualTo("CANCELLED");
        verify(schedulerClient, never()).createAttachmentDownloadTask(any());
        verify(schedulerClient, never()).cancel(any());
        verify(downloadIngestionClient, times(1)).createHttpAttachment(
                any(), anyLong(), any(), anyString(), anyString(), any(), any(), any());
        verify(downloadIngestionClient, times(2)).cancel(downloadRequestId, 11L);
    }

    @Test
    void shouldRetryDirectHttpSubmissionAfterTransientFailure() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":47, "user_id":10006,
                  "message":[{"type":"image","data":{"file":"retry.jpg","url":"https://cdn.example.test/retry.jpg"}}]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(14L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any()))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(downloadRequestId);

        assertThatThrownBy(() -> attachmentDownloadService.create(message.id(), part.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary failure");
        var retried = attachmentDownloadService.create(message.id(), part.id());

        assertThat(retried.taskId()).isNull();
        assertThat(retried.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(retried.status()).isEqualTo("SUBMITTED");
        verify(schedulerClient, never()).createAttachmentDownloadTask(any());
        verify(downloadIngestionClient, times(2)).createHttpAttachment(
                any(), anyLong(), any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void shouldCancelOnlyOwnerBoundAttachmentTask() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":144,
                 "user_id":10003,"message":[{"type":"file","data":{"file_id":"cancel-file","name":"cancel.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(111L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID taskId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(taskId);

        var job = attachmentDownloadService.create(message.id(), part.id(), 111L);
        var cancelling = attachmentDownloadService.cancel(job.id(), 111L);

        assertThat(cancelling.status()).isEqualTo("CANCELLING");
        assertThatThrownBy(() -> attachmentDownloadService.get(job.id(), 112L))
                .isInstanceOf(AttachmentDownloadNotFoundException.class);
        verify(schedulerClient).cancel(taskId);
        verify(downloadIngestionClient, never()).cancel(any(), anyLong());
    }

    @Test
    void shouldMapTimedOutDownloadCancellationToFailed() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":145,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"timeout.jpg",
                 "url":"https://cdn.example.test/timeout.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(112L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenReturn(downloadRequestId);
        when(downloadIngestionClient.cancel(downloadRequestId, 112L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "TIMED_OUT"));

        var job = attachmentDownloadService.create(message.id(), part.id(), 112L);
        var cancelled = attachmentDownloadService.cancel(job.id(), 112L);

        assertThat(cancelled.status()).isEqualTo("FAILED");
        assertThat(cancelled.lastErrorCode()).isEqualTo("DOWNLOAD_TIMED_OUT");
        verify(downloadIngestionClient).cancel(downloadRequestId, 112L);
        verify(schedulerClient, never()).cancel(any());
    }

    @Test
    void shouldKeepDurableCancellationIntentWhenDownloadCancellationFails() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":146,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"failure.jpg",
                 "url":"https://cdn.example.test/failure.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(113L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenReturn(downloadRequestId);
        when(downloadIngestionClient.cancel(downloadRequestId, 113L))
                .thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLED"));
        var job = attachmentDownloadService.create(message.id(), part.id(), 113L);

        assertThatThrownBy(() -> attachmentDownloadService.cancel(job.id(), 113L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary failure");

        assertThat(messagingRepository.findAttachmentJob(job.id()).orElseThrow().status())
                .isEqualTo("CANCELLING");
        var retried = attachmentDownloadService.create(message.id(), part.id(), 113L);
        assertThat(retried.status()).isEqualTo("CANCELLED");
        verify(downloadIngestionClient, times(2)).cancel(downloadRequestId, 113L);
        verify(schedulerClient, never()).cancel(any());
    }

    @Test
    void shouldNotOverwriteTerminalStateObservedDuringDownloadCancellation() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":147,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"race.jpg",
                 "url":"https://cdn.example.test/race.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(114L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenReturn(downloadRequestId);
        var job = attachmentDownloadService.create(message.id(), part.id(), 114L);
        when(downloadIngestionClient.cancel(downloadRequestId, 114L)).thenAnswer(invocation -> {
            messagingRepository.updateAttachmentJobStatus(job.id(), "SUCCEEDED", null);
            return new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLING");
        });

        var result = attachmentDownloadService.cancel(job.id(), 114L);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        verify(downloadIngestionClient).cancel(downloadRequestId, 114L);
    }

    @Test
    void shouldCancelDirectDownloadCreatedAfterCancellationIntent() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":148,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"direct-race.jpg",
                 "url":"https://cdn.example.test/direct-race.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(115L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenAnswer(invocation -> {
            messagingRepository.requestAttachmentCancellation(invocation.getArgument(0));
            return downloadRequestId;
        });
        when(downloadIngestionClient.cancel(downloadRequestId, 115L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLED"));

        var result = attachmentDownloadService.create(message.id(), part.id(), 115L);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.downloadRequestId()).isEqualTo(downloadRequestId);
        verify(downloadIngestionClient).cancel(downloadRequestId, 115L);
        verify(schedulerClient, never()).createAttachmentDownloadTask(any());
    }

    @Test
    void shouldCompensateDownloadCreatedAfterLocalCancellationCompleted() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":153,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"cancelled-before-bind.jpg",
                 "url":"https://cdn.example.test/cancelled-before-bind.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(120L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenAnswer(invocation -> {
            UUID jobId = invocation.getArgument(0);
            messagingRepository.requestAttachmentCancellation(jobId);
            messagingRepository.updateAttachmentJobStatus(jobId, "CANCELLED", null);
            return downloadRequestId;
        });
        when(downloadIngestionClient.cancel(downloadRequestId, 120L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLED"));

        var result = attachmentDownloadService.create(message.id(), part.id(), 120L);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.downloadRequestId()).isEqualTo(downloadRequestId);
        verify(downloadIngestionClient).cancel(downloadRequestId, 120L);
        verify(schedulerClient, never()).createAttachmentDownloadTask(any());
    }

    @Test
    void shouldNotExecuteOpaqueDownloadAfterCancellationIntent() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":149,
                 "user_id":10003,"message":[{"type":"file","data":{"file_id":"cancel-before-execute",
                 "name":"cancelled.txt"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(116L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID schedulerTaskId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(schedulerTaskId);
        var job = attachmentDownloadService.create(message.id(), part.id(), 116L);

        attachmentDownloadService.cancel(job.id(), 116L);
        var result = attachmentDownloadService.execute(job.id());

        assertThat(result.status()).isEqualTo("CANCELLING");
        assertThat(result.downloadRequestId()).isNull();
        verify(downloadIngestionClient, never()).createHttpAttachment(any(), anyLong(), any(), anyString(),
                anyString(), any(), any(), any());
        verify(downloadIngestionClient, never()).createStreamedAttachment(any(), anyLong(), any(), anyString(),
                any(), any(), any());
    }

    @Test
    void shouldCancelDownloadBoundWhileWrapperCancellationRuns() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":150,
                 "user_id":10003,"message":[{"type":"file","data":{"file_id":"bind-during-cancel",
                 "name":"race.txt"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(117L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID schedulerTaskId = UUID.randomUUID();
        UUID downloadRequestId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(schedulerTaskId);
        var job = attachmentDownloadService.create(message.id(), part.id(), 117L);
        doAnswer(invocation -> {
            messagingRepository.bindDownloadRequest(job.id(), downloadRequestId);
            return null;
        }).when(schedulerClient).cancel(schedulerTaskId);
        when(downloadIngestionClient.cancel(downloadRequestId, 117L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLED"));

        var result = attachmentDownloadService.cancel(job.id(), 117L);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.downloadRequestId()).isEqualTo(downloadRequestId);
        verify(downloadIngestionClient).cancel(downloadRequestId, 117L);
    }

    @Test
    void shouldNotRegressTerminalStateDuringDownloadReconciliation() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":151,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"get-race.jpg",
                 "url":"https://cdn.example.test/get-race.jpg"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(118L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any())).thenReturn(downloadRequestId);
        var job = attachmentDownloadService.create(message.id(), part.id(), 118L);
        when(downloadIngestionClient.get(downloadRequestId, 118L)).thenAnswer(invocation -> {
            messagingRepository.updateAttachmentJobStatus(job.id(), "CANCELLED", null);
            return new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "RUNNING");
        });

        var result = attachmentDownloadService.get(job.id(), 118L);

        assertThat(result.status()).isEqualTo("CANCELLED");
    }

    @Test
    void shouldPreserveCancellationIntentWhenOpaqueResolutionFinishesLate() throws Exception {
        var event = objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":152,
                 "user_id":10003,"message":[{"type":"file","data":{"file_id":"resolve-race",
                 "name":"late.txt"}}]}
                """);
        var message = adapter.receive(new OneBotInboundRequest(119L, "napcat-main", event));
        var part = message.parts().getFirst();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(UUID.randomUUID());
        var job = attachmentDownloadService.create(message.id(), part.id(), 119L);
        when(providerFileResolverClient.resolve("ONEBOT", "napcat-main", "FILE", "resolve-race"))
                .thenAnswer(invocation -> {
                    messagingRepository.requestAttachmentCancellation(job.id());
                    return new ProviderFileResolverClient.Resolution(
                            "PUBLIC_URL", "https://cdn.example.test/late.txt");
                });

        var result = attachmentDownloadService.resolve(job.id());

        assertThat(result.status()).isEqualTo("CANCELLING");
        assertThat(result.resolved()).isFalse();
        assertThat(messagingRepository.findAttachmentJob(job.id()).orElseThrow().status())
                .isEqualTo("CANCELLING");
    }

    @Test
    void shouldResolveOpaqueProviderFileBeforeDownloadSubmission() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":45, "user_id":10004,
                  "message":[{"type":"file","data":{"file_id":"opaque-book","name":"book.txt"}}]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(12L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID schedulerTaskId = UUID.randomUUID();
        UUID downloadRequestId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(schedulerTaskId);
        when(providerFileResolverClient.resolve("ONEBOT", "napcat-main", "FILE", "opaque-book"))
                .thenReturn(new ProviderFileResolverClient.Resolution(
                        "PUBLIC_URL", "https://cdn.example.test/book.txt"));
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(),
                any(), any(), any()))
                .thenReturn(downloadRequestId);
        when(downloadIngestionClient.cancel(downloadRequestId, 12L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "CANCELLING"));

        var job = attachmentDownloadService.create(message.id(), part.id());
        var resolved = attachmentDownloadService.resolve(job.id());
        var replay = attachmentDownloadService.resolve(job.id());
        var submitted = attachmentDownloadService.execute(job.id());
        var cancelling = attachmentDownloadService.cancel(job.id(), 12L);

        assertThat(part.providerAccountKey()).isEqualTo("napcat-main");
        assertThat(resolved.resolved()).isTrue();
        assertThat(replay.resolved()).isTrue();
        assertThat(submitted.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(cancelling.status()).isEqualTo("CANCELLING");
        verify(providerFileResolverClient, times(1)).resolve("ONEBOT", "napcat-main", "FILE", "opaque-book");
        verify(downloadIngestionClient).createHttpAttachment(any(), anyLong(), any(),
                org.mockito.ArgumentMatchers.eq("https://cdn.example.test/book.txt"), anyString(),
                any(), any(), any());
        verify(downloadIngestionClient).cancel(downloadRequestId, 12L);
        verify(schedulerClient, never()).cancel(schedulerTaskId);
    }

    @Test
    void shouldKeepAuthenticatedProviderStreamOutsideSchedulerParameters() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":46, "user_id":10005,
                  "message":[{"type":"file","data":{"file_id":"private-book","name":"private.txt"}}]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(13L, "napcat-private", event));
        var part = message.parts().getFirst();
        UUID downloadRequestId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(UUID.randomUUID());
        when(providerFileResolverClient.resolve("ONEBOT", "napcat-private", "FILE", "private-book"))
                .thenReturn(new ProviderFileResolverClient.Resolution("STREAM", null));
        when(downloadIngestionClient.createStreamedAttachment(any(), anyLong(), any(), anyString(), any(),
                any(), any()))
                .thenReturn(downloadRequestId);
        doAnswer(invocation -> {
            ((java.io.OutputStream) invocation.getArgument(4)).write("private".getBytes());
            return null;
        }).when(providerFileResolverClient).stream(anyString(), anyString(), anyString(), anyString(), any(), anyLong());

        var job = attachmentDownloadService.create(message.id(), part.id());
        attachmentDownloadService.resolve(job.id());
        var submitted = attachmentDownloadService.execute(job.id());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        attachmentDownloadService.stream(job.id(), output);

        assertThat(submitted.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(output.toString()).isEqualTo("private");
        verify(downloadIngestionClient).createStreamedAttachment(any(), anyLong(), any(), anyString(), any(),
                any(), any());
        verify(downloadIngestionClient, times(0)).createHttpAttachment(
                org.mockito.ArgumentMatchers.eq(job.id()), anyLong(), any(), anyString(), anyString(),
                any(), any(), any());
    }

    private int receivedOutboxCount(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE aggregate_id = ? AND event_type = 'MessageReceived'
                """, Integer.class, messageId.toString());
    }

    private int acceptanceOutboxCount(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM messaging_outbox
                WHERE aggregate_id = ? AND event_type = 'OneBotForwardAccepted'
                """, Integer.class, messageId.toString());
    }

    private boolean completeConcurrently(MessagingRepository.OneBotForwardExpansion job, UUID token,
                                         String providerFileId, CountDownLatch ready,
                                         CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return false;
        }
        Boolean completed = transactionTemplate.execute(status -> messagingRepository
                .completeOneBotForwardExpansion(job, token, List.of(new CreateInboundMessagePart(
                        "ATTACHMENT", null, "FILE", providerFileId, "napcat-main", null,
                        providerFileId + ".bin", null, null)), List.of(), false));
        return Boolean.TRUE.equals(completed);
    }
}
