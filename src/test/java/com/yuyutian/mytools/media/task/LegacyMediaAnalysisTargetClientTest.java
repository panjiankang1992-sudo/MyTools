package com.yuyutian.mytools.media.task;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LegacyMediaAnalysisTargetClientTest {
    @Test
    void shouldResolveLegacyIdWithInternalCredential() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MediaProcessingSidecarProperties properties = new MediaProcessingSidecarProperties();
        properties.setMediaLibraryUrl("http://media/");
        properties.setMediaLibraryToken("media-token");
        UUID mediaItemId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        server.expect(requestTo("http://media/internal/v1/media/migrations/legacy-items/42/analysis-target"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer media-token"))
                .andRespond(withSuccess("{\"mediaItemId\":\"" + mediaItemId
                        + "\",\"assetRegistryId\":\"" + assetId
                        + "\",\"ownerId\":7,\"displayName\":\"video.mp4\","
                        + "\"mimeType\":\"video/mp4\",\"sizeBytes\":100,"
                        + "\"contentSha256\":\"" + "a".repeat(64) + "\"}",
                        MediaType.APPLICATION_JSON));

        var target = new LegacyMediaAnalysisTargetClient(restTemplate, properties).resolve(42L);

        assertThat(target.mediaItemId()).isEqualTo(mediaItemId);
        assertThat(target.assetRegistryId()).isEqualTo(assetId);
        server.verify();
    }
}
