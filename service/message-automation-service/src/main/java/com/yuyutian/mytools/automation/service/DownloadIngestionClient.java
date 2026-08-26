package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 下载接入服务业务请求客户端。
 */
public class DownloadIngestionClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RestClient restClient;
    private final String internalToken;

    /**
     * 创建下载接入客户端。
     */
    public DownloadIngestionClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken;
    }

    /**
     * 幂等创建一个消息 URL 下载请求。
     *
     * @return 下载业务请求标识
     */
    public String create(UUID messageId, long ownerId, UUID ruleId, int index, String requestKind,
                         String url, String fileName) {
        String idempotencyKey = "automation:" + messageId + ":" + ruleId + ":" + index;
        Map<String, Object> payload = Map.of(
                "ownerId", ownerId,
                "idempotencyKey", idempotencyKey,
                "sourceType", "MESSAGE",
                "sourceKey", messageId.toString(),
                "requestKind", requestKind,
                "parameters", Map.of("ownerId", ownerId, "itemId", messageId + "-" + index,
                        "url", url, "fileName", fileName));
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(jsonBytes(payload)).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return response.path("id").asText();
    }

    /**
     * 查询下载子动作状态。
     */
    public DownloadSnapshot get(UUID requestId, long ownerId) {
        JsonNode response = restClient.get().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/download-requests/{id}")
                        .queryParam("ownerId", ownerId).build(requestId))
                .header("Authorization", "Bearer " + requiredToken()).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        String status = response == null ? "" : response.path("status").asText();
        if (!requestId.toString().equals(identifier) || status.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid status response");
        }
        return new DownloadSnapshot(requestId, status);
    }

    /**
     * 取消下载子动作。
     */
    public DownloadSnapshot cancel(UUID requestId, long ownerId) {
        JsonNode response = restClient.post().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/download-requests/{id}/cancel")
                        .queryParam("ownerId", ownerId).build(requestId))
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        String status = response == null ? "" : response.path("status").asText();
        if (!requestId.toString().equals(identifier) || status.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid cancel response");
        }
        return new DownloadSnapshot(requestId, status);
    }

    private String requiredToken() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("Download Ingestion internal token is missing");
        }
        return internalToken;
    }

    private byte[] jsonBytes(Map<String, Object> payload) {
        try {
            byte[] value = OBJECT_MAPPER.writeValueAsBytes(payload);
            if (value.length == 0 || value.length > 1024 * 1024) {
                throw new IllegalArgumentException("Download request body size is invalid");
            }
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Download request body is invalid", exception);
        }
    }

    /**
     * 下载子动作最小状态快照。
     */
    public record DownloadSnapshot(UUID id, String status) {
    }
}
