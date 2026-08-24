package com.yuyutian.mytools.media.task;

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

class MediaDirectoryScanSidecarClientTest {

    @Test
    void shouldCreatePersistentMediaScanWithInternalToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MediaDirectoryScanSidecarProperties properties = new MediaDirectoryScanSidecarProperties();
        properties.setMediaLibraryUrl("http://media/");
        properties.setMediaLibraryToken("media-token");
        properties.setOwnerId(1L);
        UUID operationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://media/internal/v1/media/operations/directory-scans?ownerId=1"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer media-token"))
                .andExpect(content().json("""
                        {"idempotencyKey":"scan-key","rootPath":"/media","directoryKey":"legacy-media-root",
                         "directoryName":"Legacy Media","analyze":false}
                        """))
                .andRespond(withSuccess("{\"id\":\"" + operationId + "\",\"taskInstanceId\":\""
                        + taskId + "\",\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        var accepted = new MediaDirectoryScanSidecarClient(restTemplate, properties)
                .create("/media", "scan-key");

        assertThat(accepted.id()).isEqualTo(operationId);
        assertThat(accepted.taskInstanceId()).isEqualTo(taskId);
        server.verify();
    }
}
