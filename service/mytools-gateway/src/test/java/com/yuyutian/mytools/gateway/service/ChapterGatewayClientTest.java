package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.ChapterGatewayModels.CreatePrefetch;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChapterGatewayClientTest {

    @Test
    void shouldInjectOwnerAndHideTaskId() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID id = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/chapter-prefetches"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"idempotencyKey\":\"prefetch-1\"}"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"taskId\":\"" + UUID.randomUUID()
                        + "\",\"status\":\"QUEUED\",\"requestedCount\":2,\"cachedCount\":0}",
                        MediaType.APPLICATION_JSON));

        var result = new ReaderGatewayClient(template, properties()).createPrefetch(55L,
                new CreatePrefetch("prefetch-1", sourceId, "https://source.example/book", List.of(0, 1)),
                "correlation");

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.toString()).doesNotContain("taskId");
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true, Set.of(55L),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader",
                "http://drive", "http://download", "gateway-token", "identity-token", "reader-token",
                "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
