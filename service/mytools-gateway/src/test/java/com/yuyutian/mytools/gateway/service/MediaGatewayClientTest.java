package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaGatewayClientTest {
    @Test
    void shouldBindOwnerAndForwardOnlyStableListFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://media/internal/v1/media/items?ownerId=55&includeMissing=false&limit=50"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer media-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess("{\"items\":[],\"nextAfterId\":null}",
                        MediaType.APPLICATION_JSON));
        MediaGatewayClient client = new MediaGatewayClient(restTemplate, properties());

        assertThat(client.list(55L, null, false, 50, "correlation").items()).isEmpty();
        server.verify();
    }

    @Test
    void shouldBindOwnerWhenWritingProgress() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID mediaId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-08-24T00:00:00Z");
        server.expect(requestTo("http://media/internal/v1/media/items/" + mediaId + "/progress?ownerId=55"))
                .andExpect(method(PUT)).andExpect(content().json("""
                        {"positionMs":10,"durationMs":100,"completed":false,
                         "expectedRevision":0,"clientUpdatedAt":1787529600}
                        """))
                .andRespond(withSuccess("{\"ownerId\":55,\"mediaItemId\":\"" + mediaId
                        + "\",\"positionMs\":10,\"durationMs\":100,\"completed\":false,"
                        + "\"revision\":1,\"clientUpdatedAt\":\"2026-08-24T00:00:00Z\","
                        + "\"serverUpdatedAt\":\"2026-08-24T00:00:01Z\"}",
                        MediaType.APPLICATION_JSON));
        MediaGatewayClient client = new MediaGatewayClient(restTemplate, properties());

        var result = client.progress(55L, mediaId,
                new ProgressRequest(10, 100, false, 0, updatedAt), "correlation");

        assertThat(result.ownerId()).isEqualTo(55L);
        assertThat(result.revision()).isEqualTo(1);
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token",
                "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000,
                true, "http://media", "media-token");
    }
}
