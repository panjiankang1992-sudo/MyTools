package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateManagedImport;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.MediaView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EbookImportGatewayClientTest {

    @Test
    void shouldInjectOwnerAndHideTaskId() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/ebook-imports"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"import-1\"}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"taskId\":\"" + UUID.randomUUID()
                        + "\",\"status\":\"QUEUED\",\"sourceId\":\"" + sourceId
                        + "\",\"sourceVersion\":1,\"title\":\"Book\"}", MediaType.APPLICATION_JSON));

        var result = new ReaderGatewayClient(template, properties()).createImport(55L,
                new CreateImport("import-1", sourceId, "https://source.example/book", "Book", null),
                "correlation");

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.toString()).doesNotContain("taskId");
        server.verify();
    }

    @Test
    void shouldForwardOnlyGatewayFrozenManagedMediaIdentity() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID id = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/ebook-imports/managed-media"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"managed-1\",\"mediaItemId\":\""
                        + mediaId + "\",\"mediaAssetId\":\"" + assetId + "\",\"mimeType\":\"text/plain\","
                        + "\"sizeBytes\":100,\"contentSha256\":\"" + "a".repeat(64)
                        + "\",\"rightsConfirmed\":true}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));
        MediaView media = new MediaView(mediaId, 55L, assetId, "Book.txt", "text/plain", 100L,
                "a".repeat(64), "READY", 1L, java.util.List.of(), null, null);

        var result = new ReaderGatewayClient(template, properties()).createManagedImport(55L,
                new CreateManagedImport("managed-1", mediaId, true), media, "correlation");

        assertThat(result.id()).isEqualTo(id);
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true, Set.of(55L),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader",
                "http://drive", "http://download", "gateway-token", "identity-token", "reader-token",
                "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
