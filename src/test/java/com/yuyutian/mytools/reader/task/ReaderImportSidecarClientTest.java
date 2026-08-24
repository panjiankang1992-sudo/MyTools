package com.yuyutian.mytools.reader.task;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReaderImportSidecarClientTest {

    @Test
    void shouldResolveMigratedSourceBeforeCreatingImport() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ReaderImportSidecarProperties properties = new ReaderImportSidecarProperties();
        properties.setServiceUrl("http://reader/");
        properties.setInternalToken("reader-token");
        UUID sourceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/internal/v1/book-sources/resolve?ownerId=7"
                        + "&sourceUrl=https://source.example"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess("{\"id\":\"" + sourceId
                        + "\",\"sourceUrl\":\"https://source.example\",\"version\":2}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://reader/api/v1/ebook-imports"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer reader-token"))
                .andExpect(content().json("{\"ownerId\":7,\"idempotencyKey\":"
                        + "\"legacy-source-import:legacy-1\",\"sourceId\":\"" + sourceId
                        + "\",\"bookUrl\":\"https://source.example/book\",\"title\":\"Book\"}"))
                .andRespond(withSuccess("{\"id\":\"" + requestId + "\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));
        var event = new ReaderImportSidecarRequested("legacy-1", 7L, "https://source.example",
                "https://source.example/book", "Book", null);

        var accepted = new ReaderImportSidecarClient(restTemplate, properties).create(event);

        assertThat(accepted.id()).isEqualTo(requestId);
        server.verify();
    }
}
