package com.yuyutian.mytools.localfile.service.tagging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TaggerClientTest {
    private TaggerClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate template = new RestTemplate();
        server = MockRestServiceServer.createServer(template);
        client = new TaggerClient(template, new ObjectMapper());
        ReflectionTestUtils.setField(client, "taggingServiceUrl", "http://localhost:11434");
        ReflectionTestUtils.setField(client, "taggingModel", "huihui_ai/qwen3-vl-abliterated:8b");
    }

    @Test
    void shouldRejectMissingConfiguredModel() {
        expectModels("{\"models\":[{\"name\":\"huihui_ai/qwen3-vl-abliterated:4b\"}]}");
        assertThat(client.isServiceAvailable()).isFalse();
        server.verify();
    }

    @Test
    void shouldAcceptInstalledConfiguredModel() {
        expectModels("{\"models\":[{\"name\":\"huihui_ai/qwen3-vl-abliterated:8b\"}]}");
        assertThat(client.isServiceAvailable()).isTrue();
        server.verify();
    }

    @Test
    void shouldRejectMalformedModelResponse() {
        expectModels("invalid");
        assertThat(client.isServiceAvailable()).isFalse();
        server.verify();
    }

    @Test
    void shouldRejectEmptyModelResponse() {
        expectModels(" ");
        assertThat(client.isServiceAvailable()).isFalse();
        server.verify();
    }

    @Test
    void shouldSendEightBModelAndParseStructuredTags() {
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(content().json("""
                        {"model":"huihui_ai/qwen3-vl-abliterated:8b","think":false,"format":"json","stream":false}
                        """))
                .andRespond(withSuccess("{\"message\":{\"content\":\"{\\\"tags\\\":[{\\\"tag_name\\\":\\\"nature\\\",\\\"tag_type\\\":\\\"topic\\\",\\\"confidence\\\":0.9}]}\"}}", MediaType.APPLICATION_JSON));
        assertThat(client.tagTextFile("A forest", "sample.txt"))
                .singleElement().satisfies(tag -> assertThat(tag.getTagName()).isEqualTo("nature"));
        server.verify();
    }

    private void expectModels(String response) {
        server.expect(requestTo("http://localhost:11434/api/tags"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
