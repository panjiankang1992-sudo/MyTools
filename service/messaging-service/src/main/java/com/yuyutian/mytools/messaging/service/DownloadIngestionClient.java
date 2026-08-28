package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * 下载接入服务内部客户端。
 */
public class DownloadIngestionClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String token;

    /**
     * 创建下载接入客户端。
     */
    public DownloadIngestionClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    /**
     * 幂等创建一个 HTTP 附件下载请求。
     */
    public UUID createHttpAttachment(UUID jobId, long ownerId, UUID partId, String url, String fileName,
                                     String mimeType, Long declaredSize, Instant receivedAt) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Download Ingestion internal token is missing");
        }
        long maximum = declaredSize == null ? 2L * 1024 * 1024 * 1024
                : Math.min(20L * 1024 * 1024 * 1024, Math.max(declaredSize, 1024 * 1024));
        Map<String, Object> parameters = Map.of(
                "ownerId", ownerId,
                "itemId", partId.toString(),
                "url", url,
                "fileName", fileName,
                "resourceUsername", messageResourceUsername(),
                "assetMimeType", safeMimeType(mimeType),
                "receivedAt", receivedAt.toString(),
                "maxBytes", maximum);
        Map<String, Object> request = Map.of(
                "ownerId", ownerId,
                "idempotencyKey", "message_attachment:" + jobId + ":v2",
                "sourceType", "MESSAGE_ATTACHMENT",
                "sourceKey", partId + ":" + jobId,
                "requestKind", "HTTP_ASSET",
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(jsonBytes(request)).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        if (identifier.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return UUID.fromString(identifier);
    }

    /**
     * 幂等创建一个通过 Messaging 受控流读取的附件下载请求。
     */
    public UUID createStreamedAttachment(UUID jobId, long ownerId, UUID partId, String fileName,
                                         String mimeType, Long declaredSize, Instant receivedAt) {
        long maximum = declaredSize == null ? 2L * 1024 * 1024 * 1024
                : Math.min(20L * 1024 * 1024 * 1024, Math.max(declaredSize, 1024 * 1024));
        Map<String, Object> parameters = Map.of(
                "ownerId", ownerId,
                "itemId", partId.toString(),
                "attachmentJobId", jobId.toString(),
                "fileName", fileName,
                "resourceUsername", messageResourceUsername(),
                "assetMimeType", safeMimeType(mimeType),
                "receivedAt", receivedAt.toString(),
                "maxBytes", maximum);
        return create(jobId, partId, "MESSAGE_ATTACHMENT", parameters, "v4");
    }

    private UUID create(UUID jobId, UUID partId, String requestKind, Map<String, Object> parameters,
                        String version) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Download Ingestion internal token is missing");
        }
        Map<String, Object> request = Map.of(
                "ownerId", parameters.get("ownerId"),
                "idempotencyKey", "message_attachment:" + jobId + ":" + version,
                "sourceType", "MESSAGE_ATTACHMENT",
                // 来源唯一键必须包含作业标识，显式重试不能被历史失败请求永久阻断。
                "sourceKey", partId + ":" + jobId,
                "requestKind", requestKind,
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(jsonBytes(request)).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        if (identifier.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return UUID.fromString(identifier);
    }

    /**
     * 查询下载请求的业务状态。
     */
    public DownloadSnapshot get(UUID downloadRequestId, long ownerId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Download Ingestion internal token is missing");
        }
        JsonNode response = restClient.get().uri(uriBuilder -> uriBuilder
                        .path("/internal/v1/download-requests/{id}")
                        .queryParam("ownerId", ownerId).build(downloadRequestId))
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        String status = response == null ? "" : response.path("status").asText();
        if (!downloadRequestId.toString().equals(identifier) || status.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid status response");
        }
        return new DownloadSnapshot(downloadRequestId, status);
    }

    /**
     * 下载请求最小状态快照。
     */
    public record DownloadSnapshot(UUID id, String status) {
    }

    private byte[] jsonBytes(Map<String, Object> request) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Download Ingestion request serialization failed", exception);
        }
    }

    private String messageResourceUsername() {
        String value = System.getenv().getOrDefault("MESSAGE_RESOURCE_USERNAME", "yuyutian");
        if (!value.matches("^[A-Za-z0-9._-]{1,128}$") || value.equals(".") || value.equals("..")) {
            throw new IllegalStateException("Message resource username is invalid");
        }
        return value;
    }

    private String safeMimeType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value;
    }
}
