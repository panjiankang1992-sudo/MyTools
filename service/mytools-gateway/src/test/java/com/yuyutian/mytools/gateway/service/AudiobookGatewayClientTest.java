package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.AudiobookGatewayModels.CreateAudiobookGeneration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AudiobookGatewayClientTest {

    @Test
    void shouldInjectOwnerWhenCreatingAudiobookGeneration() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID generationId = UUID.randomUUID();
        UUID ebookAssetId = UUID.randomUUID();
        server.expect(requestTo("http://reader/api/v1/audiobook-generations"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"ownerId\":55,\"ebookAssetId\":\"" + ebookAssetId
                        + "\",\"idempotencyKey\":\"audiobook-1\",\"mode\":\"FULL\","
                        + "\"rightsConfirmed\":true}"))
                .andRespond(withSuccess("{\"id\":\"" + generationId
                        + "\",\"status\":\"QUEUED\",\"currentStage\":\"TEXT_EXTRACTING\","
                        + "\"mode\":\"FULL\",\"generationVersion\":1,\"requestedChapterCount\":2,"
                        + "\"completedChapterCount\":0,\"failedChapterCount\":0,\"createdAt\":\"1970-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"1970-01-01T00:00:00Z\"}", MediaType.APPLICATION_JSON));

        var result = new ReaderGatewayClient(template, properties()).createAudiobookGeneration(55L,
                new CreateAudiobookGeneration(ebookAssetId, "audiobook-1", "FULL", true), "correlation");

        assertThat(result.id()).isEqualTo(generationId);
        assertThat(result.toString()).doesNotContain("ownerId");
        server.verify();
    }

    @Test
    void shouldInjectOwnerWhenCancellingAndRetryingAudiobookGeneration() {
        RestTemplate template = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        UUID generationId = UUID.randomUUID();
        String response = "{\"id\":\"" + generationId + "\",\"status\":\"CANCELLED\","
                + "\"currentStage\":\"CANCELLED\",\"mode\":\"FULL\",\"generationVersion\":1,"
                + "\"requestedChapterCount\":2,\"completedChapterCount\":0,\"failedChapterCount\":0,"
                + "\"createdAt\":\"1970-01-01T00:00:00Z\",\"updatedAt\":\"1970-01-01T00:00:00Z\"}";
        server.expect(requestTo("http://reader/api/v1/audiobook-generations/" + generationId + "/cancel?ownerId=55"))
                .andExpect(method(POST)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://reader/api/v1/audiobook-generations/" + generationId + "/retry?ownerId=55"))
                .andExpect(method(POST)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        ReaderGatewayClient client = new ReaderGatewayClient(template, properties());
        assertThat(client.cancelAudiobookGeneration(55L, generationId, "correlation").status()).isEqualTo("CANCELLED");
        assertThat(client.retryAudiobookGeneration(55L, generationId, "correlation").id()).isEqualTo(generationId);
        server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, true, Set.of(55L),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity", "http://reader",
                "http://drive", "http://download", "gateway-token", "identity-token", "reader-token",
                "drive-token", "download-token", 1000, 3000, false, "", "", false, "", "");
    }
}
