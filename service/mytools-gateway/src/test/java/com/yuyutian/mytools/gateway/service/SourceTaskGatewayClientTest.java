package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateDiscovery;
import com.yuyutian.mytools.gateway.model.SourceTaskGatewayModels.CreateHealthCheck;
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

class SourceTaskGatewayClientTest {

    @Test
    void shouldCreateOwnerBoundSourceTasksWithoutTaskIds() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID discoveryId = UUID.randomUUID();
        UUID healthId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/source-discoveries"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"discover-1\"}"))
                .andRespond(withSuccess("{\"id\":\"" + discoveryId + "\",\"taskId\":\""
                        + UUID.randomUUID() + "\",\"status\":\"QUEUED\",\"url\":\"https://sources.example\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://reader/api/v1/source-health-checks"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"health-1\"}"))
                .andRespond(withSuccess("{\"id\":\"" + healthId + "\",\"taskId\":\""
                        + UUID.randomUUID() + "\",\"status\":\"QUEUED\",\"keyword\":\"test\"}",
                        MediaType.APPLICATION_JSON));
        ReaderGatewayClient client = new ReaderGatewayClient(template, properties());

        var discovery = client.createDiscovery(55L,
                new CreateDiscovery("discover-1", "https://sources.example"), "correlation");
        var health = client.createHealthCheck(55L, new CreateHealthCheck("health-1", "test"), "correlation");

        assertThat(discovery.id()).isEqualTo(discoveryId);
        assertThat(health.id()).isEqualTo(healthId);
        assertThat(discovery.toString()).doesNotContain("taskId");
        assertThat(health.toString()).doesNotContain("taskId");
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true, Set.of(55L),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader",
                "http://drive", "http://download", "gateway-token", "identity-token", "reader-token",
                "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
