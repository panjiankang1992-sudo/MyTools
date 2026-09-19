package com.yuyutian.mytools.media.library.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.media.library.model.MediaModels.AssetEvent;
import com.yuyutian.mytools.media.library.model.MediaModels.BeginAnalysis;
import com.yuyutian.mytools.media.library.model.MediaModels.FailAnalysis;
import com.yuyutian.mytools.media.library.service.MediaLibraryService;
import com.yuyutian.mytools.media.library.service.MediaTaskSchedulerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MediaAnalysisFenceControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MediaLibraryService service;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private MediaTaskSchedulerClient scheduler;

    @Test
    void shouldRequireFenceHeadersAndRejectStaleExecution() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID taskInstanceId = UUID.randomUUID();
        var media = service.consume(new AssetEvent("controller-fence-" + assetId, assetId, 91L,
                "MEDIA_FILE", assetId.toString(), "fenced.mp4", "video/mp4", 10,
                "a".repeat(64), null, null, null));
        service.begin(media.id(), new BeginAnalysis("controller-fence", taskInstanceId, assetId));
        FailAnalysis request = new FailAnalysis(taskInstanceId, "FAILED", "MEDIA_ANALYSIS_FAILED");
        String endpoint = "/internal/v1/media/items/" + media.id() + "/analyses/fail";

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Task-Instance-Id", taskInstanceId)
                        .header("X-Task-Step-Name", "analysis_failure")
                        .header("X-Task-Business-Key", media.id())
                        .header("X-Task-Fencing-Token", 8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-token")
                        .header("X-Task-Instance-Id", taskInstanceId)
                        .header("X-Task-Step-Name", "analysis_failure")
                        .header("X-Task-Business-Key", media.id())
                        .header("X-Task-Fencing-Token", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDIA_003"));
    }
}
