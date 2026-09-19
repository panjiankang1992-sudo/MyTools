package com.yuyutian.mytools.automation.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Download Ingestion 租户绑定契约测试。
 */
class DownloadIngestionClientTest {

    @Test
    void shouldSubmitMagnetToPikpakWithLocalDestination() {
        UUID accountId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        String magnet = "magnet:?xt=urn:btih:" + "a".repeat(40);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(jsonPath("$.requestKind").value("MAGNET"))
                .andExpect(jsonPath("$.parameters.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.parameters.magnetUri").value(magnet))
                .andExpect(jsonPath("$.parameters.destinationRootName").value("managed"))
                .andExpect(jsonPath("$.parameters.url").doesNotExist())
                .andExpect(jsonPath("$.parameters.fileName").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"" + downloadId + "\"}", MediaType.APPLICATION_JSON));
        var client = new DownloadIngestionClient(builder.build(), "internal", accountId.toString(), "managed");
        assertThat(client.create(UUID.randomUUID(), 7L, UUID.randomUUID(), 0, "HTTP_ASSET", magnet,
                "magnet-0", Instant.now())).isEqualTo(downloadId.toString());
        server.verify();
    }

    @Test
    void shouldRejectUnconfiguredPikpakWithoutSubmittingLocalDownload() {
        var client = new DownloadIngestionClient(RestClient.create(), "internal", "", "managed");
        assertThatThrownBy(() -> client.create(UUID.randomUUID(), 7L, UUID.randomUUID(), 0,
                "HTTP_ASSET", "magnet:?xt=urn:btih:" + "a".repeat(40), "magnet-0", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSendAuthoritativeOwnerWhenCreatingAction() {
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer download-token"))
                .andExpect(jsonPath("$.ownerId").value(17))
                .andExpect(jsonPath("$.sourceKey").value(messageId + ":2"))
                .andExpect(jsonPath("$.parameters.ownerId").value(17))
                .andExpect(jsonPath("$.parameters.messageBatchId").value(messageId.toString()))
                .andExpect(jsonPath("$.parameters.receivedAt").value("2026-08-26T07:53:08Z"))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.create(messageId, 17L, ruleId, 2, "HTTP_ASSET",
                "https://example.test/file", "file.bin", Instant.parse("2026-08-26T07:53:08Z")))
                .isEqualTo(downloadId.toString());
        server.verify();
    }

    @Test
    void shouldUseOwnerBoundStatusAndCancelRoutes() {
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String base = "http://download.test/internal/v1/download-requests/" + downloadId;
        server.expect(requestTo(base + "?ownerId=17")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response(downloadId, "RUNNING"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/cancel?ownerId=17")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(response(downloadId, "CANCELLED"), MediaType.APPLICATION_JSON));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.get(downloadId, 17L).status()).isEqualTo("RUNNING");
        assertThat(client.cancel(downloadId, 17L).status()).isEqualTo("CANCELLED");
        server.verify();
    }

    @Test
    void shouldUseOwnerBoundResultSummaryRoute() {
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/internal/v1/download-requests/"
                        + downloadId + "/result-summary?ownerId=17"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer download-token"))
                .andRespond(withSuccess("""
                        {"downloadRequestId":"%s","status":"FAILED","items":[
                          {"fileName":"saved.jpg","tagStatus":"FAILED","tags":[]}
                        ]}
                        """.formatted(downloadId), MediaType.APPLICATION_JSON));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        DownloadIngestionClient.DownloadSummary summary = client.summary(downloadId, 17L);

        assertThat(summary.status()).isEqualTo("FAILED");
        assertThat(summary.items()).extracting(DownloadIngestionClient.DownloadItem::fileName)
                .containsExactly("saved.jpg");
        server.verify();
    }

    @Test
    void shouldRejectResultSummaryWithoutItemsArray() {
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/internal/v1/download-requests/"
                        + downloadId + "/result-summary?ownerId=17"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"downloadRequestId\":\"" + downloadId
                        + "\",\"status\":\"FAILED\",\"items\":null}", MediaType.APPLICATION_JSON));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThatThrownBy(() -> client.summary(downloadId, 17L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Download Ingestion returned invalid result summary items");
        server.verify();
    }

    @Test
    void shouldRouteXStatusToXPostTask() {
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.requestKind").value("X_POST"))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.create(messageId, 17L, ruleId, 0, "HTTP_ASSET",
                "https://mobile.x.com/user/status/123456", "123456", Instant.parse("2026-08-26T07:53:08Z")))
                .isEqualTo(downloadId.toString());
        server.verify();
    }

    @Test
    void shouldRouteXUserPageToXUserTask() {
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.requestKind").value("X_USER"))
                .andExpect(jsonPath("$.parameters.url").value("https://x.com/example/media"))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.create(messageId, 17L, ruleId, 0, "HTTP_ASSET",
                "https://x.com/example/media", "media", Instant.parse("2026-08-26T07:53:08Z")))
                .isEqualTo(downloadId.toString());
        server.verify();
    }

    @Test
    void shouldCreateOneMessageBatchWithOrderedUrlItems() {
        UUID messageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID downloadId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://download.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://download.test/api/v1/download-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idempotencyKey")
                        .value("automation-batch:" + messageId + ":" + ruleId + ":3"))
                .andExpect(jsonPath("$.sourceKey").value(messageId + ":3"))
                .andExpect(jsonPath("$.requestKind").value("MESSAGE_URL_BATCH"))
                .andExpect(jsonPath("$.parameters.messageBatchId").value(messageId.toString()))
                .andExpect(jsonPath("$.parameters.albumTitleText").value("海边写真"))
                .andExpect(jsonPath("$.parameters.items.length()").value(2))
                .andExpect(jsonPath("$.parameters.items[0].url").value("https://x.com/a/status/1"))
                .andExpect(jsonPath("$.parameters.items[1].url").value("https://cdn.example/b.jpg"))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.createBatch(messageId, 17L, ruleId, 3,
                List.of("https://x.com/a/status/1", "https://cdn.example/b.jpg"),
                Instant.parse("2026-08-26T07:53:08Z"), "海边写真")).isEqualTo(downloadId.toString());
        server.verify();
    }

    private String response(UUID id, String status) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\"}";
    }
}
