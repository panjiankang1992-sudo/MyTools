package com.yuyutian.mytools.messaging.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 下载接入客户端契约测试。
 */
class DownloadIngestionClientTest {

    @Test
    void shouldCreateIdempotentHttpDownloadWithMessageOwnership() {
        UUID jobId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer download-token"))
                .andExpect(jsonPath("$.idempotencyKey").value("message_attachment:" + jobId + ":v1"))
                .andExpect(jsonPath("$.ownerId").value(19))
                .andExpect(jsonPath("$.requestKind").value("HTTP_ASSET"))
                .andExpect(jsonPath("$.parameters.ownerId").value(19))
                .andExpect(jsonPath("$.parameters.itemId").value(partId.toString()))
                .andExpect(jsonPath("$.parameters.url").value("https://cdn.example.test/a.jpg"))
                .andRespond(withAccepted().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        UUID created = client.createHttpAttachment(jobId, 19L, partId,
                "https://cdn.example.test/a.jpg", "a.jpg", 1024L);

        assertThat(created).isEqualTo(downloadId);
        server.verify();
    }

    @Test
    void shouldReadDownloadLifecycleStatus() {
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/internal/v1/download-requests/" + downloadId
                        + "?ownerId=19"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer download-token"))
                .andRespond(withSuccess("{\"id\":\"" + downloadId + "\",\"status\":\"SUCCEEDED\"}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        DownloadIngestionClient.DownloadSnapshot snapshot = client.get(downloadId, 19L);

        assertThat(snapshot.status()).isEqualTo("SUCCEEDED");
        server.verify();
    }

    @Test
    void shouldCreateOpaqueStreamDownloadWithoutProviderReference() {
        UUID jobId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(jsonPath("$.requestKind").value("MESSAGE_ATTACHMENT"))
                .andExpect(jsonPath("$.parameters.attachmentJobId").value(jobId.toString()))
                .andExpect(jsonPath("$.parameters.url").doesNotExist())
                .andExpect(jsonPath("$.parameters.providerFileId").doesNotExist())
                .andRespond(withAccepted().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        UUID created = client.createStreamedAttachment(jobId, 19L, partId, "private.bin", 1024L);

        assertThat(created).isEqualTo(downloadId);
        server.verify();
    }
}
