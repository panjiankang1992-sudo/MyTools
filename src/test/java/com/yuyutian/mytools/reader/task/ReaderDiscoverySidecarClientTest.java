package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReaderDiscoverySidecarClientTest {

    @Test
    void shouldCreatePersistentDiscoveryWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ReaderDiscoverySidecarProperties properties = new ReaderDiscoverySidecarProperties();
        properties.setServiceUrl("http://reader/");
        properties.setInternalToken("reader-token");
        UUID requestId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/source-discoveries"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer reader-token"))
                .andExpect(content().json("""
                        {"ownerId":7,"idempotencyKey":"legacy-source-discovery:legacy-1",
                         "url":"https://repository.example/sources.json"}
                        """))
                .andRespond(withSuccess("{\"id\":\"" + requestId + "\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));
        var event = new ReaderDiscoverySidecarRequested(
                "legacy-1", 7L, "https://repository.example/sources.json");

        var accepted = new ReaderDiscoverySidecarClient(restTemplate, properties).create(event);

        assertThat(accepted.id()).isEqualTo(requestId);
        server.verify();
    }
}
