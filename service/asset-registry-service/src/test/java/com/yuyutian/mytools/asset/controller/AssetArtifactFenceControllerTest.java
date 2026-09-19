package com.yuyutian.mytools.asset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.asset.model.RegisterArtifactRequest;
import com.yuyutian.mytools.asset.model.RegisterAssetRequest;
import com.yuyutian.mytools.asset.service.AssetRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AssetArtifactFenceControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AssetRegistryService service;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRequireArtifactFenceAndRejectStaleExecution() throws Exception {
        UUID identity = UUID.randomUUID();
        var parent = service.register(new RegisterAssetRequest(92L, "http-parent:" + identity, "MEDIA",
                "http-parent:" + identity, "a".repeat(64), 100, "video/mp4", null));
        var child = service.register(new RegisterAssetRequest(92L, "http-child:" + identity, "MEDIA_ARTIFACT",
                "http-child:" + identity, "b".repeat(64), 10, "image/jpeg", null));
        var request = new RegisterArtifactRequest(parent.version(), child.id(), "http-link:" + identity,
                "THUMBNAIL", "media_generate_thumbnail", "1.0.0");
        UUID taskInstanceId = UUID.randomUUID();
        String endpoint = "/internal/v1/assets/" + parent.id() + "/artifacts";

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-asset-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-asset-token")
                        .header("X-Task-Instance-Id", taskInstanceId)
                        .header("X-Task-Step-Name", "register_thumbnail")
                        .header("X-Task-Business-Key", request.idempotencyKey())
                        .header("X-Task-Fencing-Token", 9)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());

        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer test-asset-token")
                        .header("X-Task-Instance-Id", taskInstanceId)
                        .header("X-Task-Step-Name", "register_thumbnail")
                        .header("X-Task-Business-Key", request.idempotencyKey())
                        .header("X-Task-Fencing-Token", 8)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_008"));
    }
}
