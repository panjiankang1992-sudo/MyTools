package com.yuyutian.mytools.media.library.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaTaskSchedulerClientTest {

    @Test
    void shouldSendBusinessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                .andExpect(header("X-Task-Business-Token", "business-secret"))
                .andRespond(withSuccess("{\"id\":\"" + taskId + "\"}", MediaType.APPLICATION_JSON));

        MediaTaskSchedulerClient client = new MediaTaskSchedulerClient(builder.build(), "business-secret");
        client.createAnalysis(UUID.randomUUID(), "analysis-1", Map.of("mediaId", UUID.randomUUID().toString()));

        server.verify();
    }
    @Test
    void routesPreviewToFrameOnlyTaskAndKeepsFullAnalysisSeparate() {
        for (String version : new String[]{"download-preview-v1", "history-preview-v1", "analysis-v2"}) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://scheduler");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://scheduler/api/v1/task-instances"))
                    .andExpect(jsonPath("$.taskName").value(version.equals("analysis-v2")
                            ? "media_analyze_video" : "media_generate_video_preview"))
                    .andRespond(withSuccess("{\"id\":\"" + UUID.randomUUID() + "\"}", MediaType.APPLICATION_JSON));
            new MediaTaskSchedulerClient(builder.build(), "token").createAnalysis(UUID.randomUUID(),
                    "preview", Map.of("analysisVersion", version));
            server.verify();
        }
    }
}
