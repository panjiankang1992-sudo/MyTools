package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.messaging.model.OneBotInboundRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.UUID;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:messaging_onebot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
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
    void shouldCreateOneDownloadChildTaskForHttpAttachment() throws Exception {
        var event = objectMapper.readTree("""
                {
                  "post_type":"message", "message_type":"private", "self_id":90001,
                  "message_id":44, "user_id":10003,
                  "message":[{"type":"image","data":{"file":"a.jpg","url":"https://cdn.example.test/a.jpg"}}]
                }
                """);
        var message = adapter.receive(new OneBotInboundRequest(11L, "napcat-main", event));
        var part = message.parts().getFirst();
        UUID schedulerTaskId = UUID.randomUUID();
        UUID downloadRequestId = UUID.randomUUID();
        when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(schedulerTaskId);
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(), any()))
                .thenReturn(downloadRequestId);
        when(downloadIngestionClient.get(downloadRequestId, 11L))
                .thenReturn(new DownloadIngestionClient.DownloadSnapshot(downloadRequestId, "SUCCEEDED"));

        var job = attachmentDownloadService.create(message.id(), part.id());
        var replay = attachmentDownloadService.create(message.id(), part.id());
        var submitted = attachmentDownloadService.execute(job.id());
        var executeReplay = attachmentDownloadService.execute(job.id());
        var reconciled = attachmentDownloadService.get(job.id());

        assertThat(replay.id()).isEqualTo(job.id());
        assertThat(job.taskId()).isEqualTo(schedulerTaskId);
        assertThat(submitted.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(executeReplay.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(reconciled.status()).isEqualTo("SUCCEEDED");
        verify(schedulerClient, times(1)).createAttachmentDownloadTask(job.id());
        verify(downloadIngestionClient, times(1)).createHttpAttachment(
                any(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void shouldCancelOnlyOwnerBoundAttachmentTask() throws Exception {
        var event=objectMapper.readTree("""
                {"post_type":"message","message_type":"private","self_id":90001,"message_id":144,
                 "user_id":10003,"message":[{"type":"image","data":{"file":"cancel.jpg","url":"https://cdn.example.test/cancel.jpg"}}]}
                """);
        var message=adapter.receive(new OneBotInboundRequest(111L,"napcat-main",event));var part=message.parts().getFirst();UUID taskId=UUID.randomUUID();when(schedulerClient.createAttachmentDownloadTask(any())).thenReturn(taskId);var job=attachmentDownloadService.create(message.id(),part.id(),111L);assertThat(attachmentDownloadService.cancel(job.id(),111L).status()).isEqualTo("CANCELLING");assertThatThrownBy(()->attachmentDownloadService.get(job.id(),112L)).isInstanceOf(AttachmentDownloadNotFoundException.class);verify(schedulerClient).cancel(taskId);
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
        when(providerFileResolverClient.resolve("napcat-main", "FILE", "opaque-book"))
                .thenReturn(new ProviderFileResolverClient.Resolution(
                        "PUBLIC_URL", "https://cdn.example.test/book.txt"));
        when(downloadIngestionClient.createHttpAttachment(any(), anyLong(), any(), anyString(), anyString(), any()))
                .thenReturn(downloadRequestId);

        var job = attachmentDownloadService.create(message.id(), part.id());
        var resolved = attachmentDownloadService.resolve(job.id());
        var replay = attachmentDownloadService.resolve(job.id());
        var submitted = attachmentDownloadService.execute(job.id());

        assertThat(part.providerAccountKey()).isEqualTo("napcat-main");
        assertThat(resolved.resolved()).isTrue();
        assertThat(replay.resolved()).isTrue();
        assertThat(submitted.downloadRequestId()).isEqualTo(downloadRequestId);
        verify(providerFileResolverClient, times(1)).resolve("napcat-main", "FILE", "opaque-book");
        verify(downloadIngestionClient).createHttpAttachment(any(), anyLong(), any(),
                org.mockito.ArgumentMatchers.eq("https://cdn.example.test/book.txt"), anyString(), any());
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
        when(providerFileResolverClient.resolve("napcat-private", "FILE", "private-book"))
                .thenReturn(new ProviderFileResolverClient.Resolution("STREAM", null));
        when(downloadIngestionClient.createStreamedAttachment(any(), anyLong(), any(), anyString(), any()))
                .thenReturn(downloadRequestId);
        doAnswer(invocation -> {
            ((java.io.OutputStream) invocation.getArgument(3)).write("private".getBytes());
            return null;
        }).when(providerFileResolverClient).stream(anyString(), anyString(), anyString(), any(), anyLong());

        var job = attachmentDownloadService.create(message.id(), part.id());
        attachmentDownloadService.resolve(job.id());
        var submitted = attachmentDownloadService.execute(job.id());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        attachmentDownloadService.stream(job.id(), output);

        assertThat(submitted.downloadRequestId()).isEqualTo(downloadRequestId);
        assertThat(output.toString()).isEqualTo("private");
        verify(downloadIngestionClient).createStreamedAttachment(any(), anyLong(), any(), anyString(), any());
        verify(downloadIngestionClient, times(0)).createHttpAttachment(
                org.mockito.ArgumentMatchers.eq(job.id()), anyLong(), any(), anyString(), anyString(), any());
    }
}
