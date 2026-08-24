package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.DownloadGatewayModels.CreateHttpDownload;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DownloadGatewayClientTest {
    private final UUID requestId = UUID.randomUUID();

    @Test
    void shouldInjectOwnerAndReturnOnlyStableFieldsWhenCreating() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://download/api/v1/download-requests"))
                .andExpect(method(POST)).andExpect(header("Authorization", "Bearer download-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andExpect(content().json("""
                        {"ownerId":55,"idempotencyKey":"gateway:55:asset-1",
                         "sourceType":"GATEWAY_HTTP","sourceKey":"55:asset-1",
                         "requestKind":"HTTP_ASSET","parameters":{"ownerId":55,
                         "itemId":"asset-1","url":"https://example.org/file","fileName":"file.bin",
                         "maxBytes":21474836480}}
                        """))
                .andRespond(withSuccess(response("RUNNING"), MediaType.APPLICATION_JSON));
        DownloadGatewayClient client = new DownloadGatewayClient(template, properties());

        var result = client.createHttp(new CreateHttpDownload("asset-1",
                "https://example.org/file", "file.bin", null), 55L, "correlation");

        assertThat(result.id()).isEqualTo(requestId);
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.toString()).doesNotContain("taskInstanceId", "secret.example");
        server.verify();
    }

    @Test
    void shouldBindOwnerForQueryAndMapNotFound() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://download/internal/v1/download-requests/" + requestId
                        + "?ownerId=55")).andExpect(method(GET)).andRespond(withResourceNotFound());
        DownloadGatewayClient client = new DownloadGatewayClient(template, properties());

        assertThatThrownBy(() -> client.get(requestId, 55L, "correlation"))
                .isInstanceOf(GatewayNotFoundException.class);
        server.verify();
    }

    @Test
    void shouldBindOwnerWhenCancelling() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://download/internal/v1/download-requests/" + requestId
                        + "/cancel?ownerId=55")).andExpect(method(POST))
                .andRespond(withSuccess(response("CANCELLED"), MediaType.APPLICATION_JSON));
        DownloadGatewayClient client = new DownloadGatewayClient(template, properties());

        assertThat(client.cancel(requestId, 55L, "correlation").status()).isEqualTo("CANCELLED");
        server.verify();
    }

    private String response(String status) {
        return "{\"id\":\"" + requestId + "\",\"status\":\"" + status
                + "\",\"task_instance_id\":null,\"created_at\":\"2026-08-24T00:00:00Z\","
                + "\"updated_at\":\"2026-08-24T00:00:00Z\",\"parameters\":{"
                + "\"url\":\"https://secret.example/file\"}}";
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                false, Set.of(), true, Set.of(55L), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token", "identity-token",
                "reader-token", "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
