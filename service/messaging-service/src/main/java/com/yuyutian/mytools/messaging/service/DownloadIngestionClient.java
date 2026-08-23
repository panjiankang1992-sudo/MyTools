package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 下载接入服务内部客户端。
 */
public class DownloadIngestionClient {

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
                                     Long declaredSize) {
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
                "maxBytes", maximum);
        Map<String, Object> request = Map.of(
                "ownerId", ownerId,
                "idempotencyKey", "message_attachment:" + jobId + ":v1",
                "sourceType", "MESSAGE_ATTACHMENT",
                "sourceKey", partId.toString(),
                "requestKind", "HTTP_ASSET",
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
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
                                         Long declaredSize) {
        long maximum = declaredSize == null ? 2L * 1024 * 1024 * 1024
                : Math.min(20L * 1024 * 1024 * 1024, Math.max(declaredSize, 1024 * 1024));
        Map<String, Object> parameters = Map.of(
                "ownerId", ownerId,
                "itemId", partId.toString(),
                "attachmentJobId", jobId.toString(),
                "fileName", fileName,
                "maxBytes", maximum);
        return create(jobId, partId, "MESSAGE_ATTACHMENT", parameters, "v2");
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
                "sourceKey", partId.toString(),
                "requestKind", requestKind,
                "parameters", parameters);
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
        String identifier = response == null ? "" : response.path("id").asText();
        if (identifier.isBlank()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return UUID.fromString(identifier);
    }

    /**
     * 查询下载请求的业务状态。
     */
    public DownloadSnapshot get(UUID downloadRequestId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Download Ingestion internal token is missing");
        }
        JsonNode response = restClient.get().uri("/api/v1/download-requests/{id}", downloadRequestId)
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
}
