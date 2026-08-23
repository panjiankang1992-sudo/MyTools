package com.yuyutian.mytools.localfile.service.tagging;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaTagSidecarTaskPublisherTest {

    @Test
    void shouldCreateIdempotentSidecarTaskWhenEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MediaTagSidecarProperties properties = new MediaTagSidecarProperties();
        properties.setEnabled(true);
        properties.setSchedulerUrl("http://scheduler:23210/");
        MediaTagSidecarTaskPublisher publisher = new MediaTagSidecarTaskPublisher(restTemplate, properties);
        String hash = "a".repeat(64);
        server.expect(requestTo("http://scheduler:23210/api/v1/task-instances"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskName").value("media_generate_tags"))
                .andExpect(jsonPath("$.idempotencyKey")
                        .value("media_generate_tags:" + hash + ":media-tags-v1"))
                .andExpect(jsonPath("$.parameters.contentSha256").value(hash))
                .andExpect(jsonPath("$.parameters.legacyTags[0]").value("legacy"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        publisher.publish(new MediaTagSidecarTaskRequested(
                42L, "sample.jpg", "/data/sample.jpg", "/data/thumb.jpg", "image/jpeg", hash,
                java.util.List.of("legacy")));

        server.verify();
    }

    @Test
    void shouldNotCallSchedulerWhenDisabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MediaTagSidecarTaskPublisher publisher = new MediaTagSidecarTaskPublisher(
                restTemplate, new MediaTagSidecarProperties());

        publisher.publish(new MediaTagSidecarTaskRequested(
                42L, "sample.jpg", "/data/sample.jpg", null, "image/jpeg", "a".repeat(64), java.util.List.of()));

        server.verify();
    }
}
