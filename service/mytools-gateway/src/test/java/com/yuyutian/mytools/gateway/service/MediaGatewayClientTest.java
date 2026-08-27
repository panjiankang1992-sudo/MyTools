package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.ProgressRequest;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartDirectoryScan;
import com.yuyutian.mytools.gateway.model.MediaGatewayModels.StartAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaGatewayClientTest {
    @Test
    void shouldEncodeChineseCatalogFiltersExactlyOnce() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://media/internal/v1/media/catalog?ownerId=55&mimePrefix=image/"
                        + "&directoryId=&tag=%E5%8F%8C%E9%A9%AC%E5%B0%BE&keyword=%E5%9C%B0%E9%93%81&page=1"
                        + "&pageSize=100&excludeAdult=false"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":1,\"pageSize\":100,\"tags\":[],\"directories\":[]}",
                        MediaType.APPLICATION_JSON));

        var result = new MediaGatewayClient(restTemplate, properties())
                .catalog(55L, "image/", "", "双马尾", "地铁", 1, 100, false, "correlation");

        assertThat(result.total()).isZero();
        server.verify();
    }

    @Test
    void shouldBindOwnerWhenDeletingMedia() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID mediaId = UUID.randomUUID();
        server.expect(requestTo("http://media/internal/v1/media/items/" + mediaId + "?ownerId=55"))
                .andExpect(method(DELETE))
                .andExpect(header("Authorization", "Bearer media-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess());

        new MediaGatewayClient(restTemplate, properties()).delete(55L, mediaId, "correlation");

        server.verify();
    }

    @Test
    void shouldBindOwnerAndForwardOnlyStableListFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://media/internal/v1/media/items?ownerId=55&includeMissing=false&limit=50"))
                .andExpect(method(GET)).andExpect(header("Authorization", "Bearer media-token"))
                .andExpect(header("X-Correlation-Id", "correlation"))
                .andRespond(withSuccess("{\"items\":[],\"nextAfterId\":null}",
                        MediaType.APPLICATION_JSON));
        MediaGatewayClient client = new MediaGatewayClient(restTemplate, properties());

        assertThat(client.list(55L, null, false, null, 50, "correlation").items()).isEmpty();
        server.verify();
    }

    @Test
    void shouldBindOwnerWhenWritingProgress() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID mediaId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-08-24T00:00:00Z");
        server.expect(requestTo("http://media/internal/v1/media/items/" + mediaId + "/progress?ownerId=55"))
                .andExpect(method(PUT)).andExpect(content().json("""
                        {"positionMs":10,"durationMs":100,"completed":false,
                         "expectedRevision":0,"clientUpdatedAt":1787529600}
                        """))
                .andRespond(withSuccess("{\"ownerId\":55,\"mediaItemId\":\"" + mediaId
                        + "\",\"positionMs\":10,\"durationMs\":100,\"completed\":false,"
                        + "\"revision\":1,\"clientUpdatedAt\":\"2026-08-24T00:00:00Z\","
                        + "\"serverUpdatedAt\":\"2026-08-24T00:00:01Z\"}",
                        MediaType.APPLICATION_JSON));
        MediaGatewayClient client = new MediaGatewayClient(restTemplate, properties());

        var result = client.progress(55L, mediaId,
                new ProgressRequest(10, 100, false, 0, updatedAt), "correlation");

        assertThat(result.ownerId()).isEqualTo(55L);
        assertThat(result.revision()).isEqualTo(1);
        server.verify();
    }

    @Test
    void shouldBindOwnerWhenStartingDirectoryScan() {
        RestTemplate restTemplate=new RestTemplate();MockRestServiceServer server=MockRestServiceServer.bindTo(restTemplate).build();UUID operationId=UUID.randomUUID();UUID taskId=UUID.randomUUID();server.expect(requestTo("http://media/internal/v1/media/operations/directory-scans?ownerId=55")).andExpect(method(POST)).andExpect(header("Authorization","Bearer media-token")).andExpect(content().json("{\"idempotencyKey\":\"scan-1\",\"rootPath\":\"/media/movies\",\"directoryKey\":\"movies\",\"directoryName\":\"Movies\",\"analyze\":true,\"analysisVersion\":\"analysis-v1\"}")).andRespond(withSuccess("{\"id\":\""+operationId+"\",\"ownerId\":55,\"operationType\":\"DIRECTORY_SCAN\",\"taskInstanceId\":\""+taskId+"\",\"status\":\"PENDING\",\"createdAt\":\"2026-08-24T00:00:00Z\",\"updatedAt\":\"2026-08-24T00:00:00Z\"}",MediaType.APPLICATION_JSON));var result=new MediaGatewayClient(restTemplate,properties()).startDirectoryScan(55L,new StartDirectoryScan("scan-1","/media/movies","movies","Movies",true,"analysis-v1"),"correlation");assertThat(result.id()).isEqualTo(operationId);assertThat(result.toString()).doesNotContain("taskInstanceId");server.verify();
    }

    @Test
    void shouldBindOwnerAndExcludePathsWhenStartingAnalysis() {
        RestTemplate restTemplate=new RestTemplate();MockRestServiceServer server=MockRestServiceServer.bindTo(restTemplate).build();UUID mediaId=UUID.randomUUID();UUID operationId=UUID.randomUUID();server.expect(requestTo("http://media/internal/v1/media/items/"+mediaId+"/analysis-operations?ownerId=55")).andExpect(method(POST)).andExpect(header("Authorization","Bearer media-token")).andExpect(content().json("{\"idempotencyKey\":\"analysis-1\",\"analysisVersion\":\"analysis-v2\",\"frameCount\":8,\"seekSeconds\":1.5}")).andRespond(withSuccess("{\"id\":\""+operationId+"\",\"ownerId\":55,\"operationType\":\"ANALYSIS\",\"status\":\"PENDING\",\"createdAt\":\"2026-08-24T00:00:00Z\",\"updatedAt\":\"2026-08-24T00:00:00Z\"}",MediaType.APPLICATION_JSON));var result=new MediaGatewayClient(restTemplate,properties()).startAnalysis(55L,mediaId,new StartAnalysis("analysis-1","analysis-v2",8,1.5),"correlation");assertThat(result.id()).isEqualTo(operationId);server.verify();
    }

    private GatewayProperties properties() {
        return new GatewayProperties(GatewayProperties.IdentityMode.LEGACY, false, false, Set.of(),
                false, Set.of(), false, Set.of(), "http://mytools", "http://identity",
                "http://reader", "http://drive", "http://download", "gateway-token",
                "identity-token", "reader-token", "drive-token", "download-token", 1000, 3000,
                true, "http://media", "media-token", false, "", "");
    }
}
