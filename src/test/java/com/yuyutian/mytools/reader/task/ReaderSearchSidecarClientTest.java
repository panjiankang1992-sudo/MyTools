package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReaderSearchSidecarClientTest {
    @Test
    void shouldCreatePersistentReaderSearchWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ReaderSearchSidecarProperties properties = new ReaderSearchSidecarProperties();
        properties.setServiceUrl("http://reader/");
        properties.setInternalToken("reader-token");
        UUID requestId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/book-searches"))
                .andExpect(method(POST)).andExpect(header("Authorization", "Bearer reader-token"))
                .andExpect(content().json("""
                        {"ownerId":7,"idempotencyKey":"shadow-key","keyword":"Book","mode":"FUZZY",
                         "page":1,"sources":[{"id":"source-1","name":"Source",
                         "url":"https://source.example","revision":1,"snapshot":{"enabled":true}}]}
                        """))
                .andRespond(withSuccess("{\"id\":\"" + requestId
                        + "\",\"status\":\"QUEUED\",\"results\":[]}", MediaType.APPLICATION_JSON));
        ReaderSearchSidecarRequested event = new ReaderSearchSidecarRequested(
                7L, "Book", 1, "FUZZY", List.of(Map.of(
                "id", "source-1", "name", "Source", "url", "https://source.example",
                "revision", 1, "snapshot", Map.of("enabled", true))));

        var accepted = new ReaderSearchSidecarClient(restTemplate, properties).create(event, "shadow-key");

        assertThat(accepted.id()).isEqualTo(requestId);
        server.verify();
    }
}
