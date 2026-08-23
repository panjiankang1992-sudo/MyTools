package com.yuyutian.mytools.automation.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * Download Ingestion 租户绑定契约测试。
 */
class DownloadIngestionClientTest {

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
                .andExpect(jsonPath("$.parameters.ownerId").value(17))
                .andRespond(withAccepted().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + downloadId + "\"}"));
        DownloadIngestionClient client = new DownloadIngestionClient(builder.build(), "download-token");

        assertThat(client.create(messageId, 17L, ruleId, 0, "HTTP_ASSET",
                "https://example.test/file", "file.bin")).isEqualTo(downloadId.toString());
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

    private String response(UUID id, String status) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\"}";
    }
}
