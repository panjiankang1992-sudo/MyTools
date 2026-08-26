package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 下载接入服务业务请求客户端。
 */
public class DownloadIngestionClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> X_HOSTS = Set.of("x.com", "twitter.com");
    private static final Pattern X_STATUS_PATH = Pattern.compile(
            "^/(?:[^/]+/status|i/(?:web/)?status)/[0-9]{1,24}(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);
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
                         String url, String fileName, Instant receivedAt) {
        String idempotencyKey = "automation:" + messageId + ":" + ruleId + ":" + index;
        String effectiveRequestKind = effectiveRequestKind(requestKind, url);
        Map<String, Object> payload = Map.of(
                "ownerId", ownerId,
                "idempotencyKey", idempotencyKey,
                "sourceType", "MESSAGE",
                // 同一消息可包含多个下载动作，来源键必须包含稳定序号。
                "sourceKey", messageId + ":" + index,
                "requestKind", effectiveRequestKind,
                "parameters", Map.of("ownerId", ownerId, "itemId", messageId + "-" + index,
                        "url", url, "fileName", fileName, "messageBatchId", messageId.toString(),
                        "receivedAt", receivedAt.toString()));
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(jsonBytes(payload)).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return response.path("id").asText();
    }

    /**
     * 幂等创建一个整消息 URL 下载批次。
     *
     * @param messageId 标准消息标识
     * @param ownerId 所有者标识
     * @param ruleId 自动化规则标识
     * @param urls 消息内按出现顺序去重后的链接
     * @param receivedAt 消息接收时间
     * @return 下载业务请求标识
     */
    public String createBatch(UUID messageId, long ownerId, UUID ruleId, List<String> urls, Instant receivedAt) {
        if (urls == null || urls.size() < 2 || urls.size() > 20) {
            throw new IllegalArgumentException("Message URL batch size is invalid");
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int index = 0; index < urls.size(); index++) {
            String url = urls.get(index);
            items.add(Map.of("url", url, "fileName", fileName(url, index)));
        }
        Map<String, Object> payload = Map.of(
                "ownerId", ownerId,
                "idempotencyKey", "automation-batch:" + messageId + ":" + ruleId,
                "sourceType", "MESSAGE",
                "sourceKey", messageId.toString(),
                "requestKind", "MESSAGE_URL_BATCH",
                "parameters", Map.of("ownerId", ownerId, "messageBatchId", messageId.toString(),
                        "receivedAt", receivedAt.toString(), "items", List.copyOf(items)));
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .header("Authorization", "Bearer " + requiredToken())
                .contentType(MediaType.APPLICATION_JSON).body(jsonBytes(payload)).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return response.path("id").asText();
    }

    private String effectiveRequestKind(String requestKind, String url) {
        if (!"HTTP_ASSET".equals(requestKind)) {
            return requestKind;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            host = host.replaceFirst("^(?:www\\.|mobile\\.)", "");
            if (X_HOSTS.contains(host) && X_STATUS_PATH.matcher(uri.getPath()).matches()) {
                return "X_POST";
            }
        } catch (IllegalArgumentException ignored) {
            // URL 有效性由下载服务统一校验，此处仅做业务类型识别。
        }
        return requestKind;
    }

    private String fileName(String url, int index) {
        try {
            String path = URI.create(url).getPath();
            String raw = path == null || path.isBlank() || path.endsWith("/")
                    ? "download-" + index + ".bin" : path.substring(path.lastIndexOf('/') + 1);
            String safe = raw.replaceAll("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]", "_");
            return safe.isBlank() ? "download-" + index + ".bin"
                    : safe.substring(0, Math.min(180, safe.length()));
        } catch (IllegalArgumentException exception) {
            return "download-" + index + ".bin";
        }
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
     * 查询下载文件名和终态标签，不返回来源地址或物理路径。
     */
    public DownloadSummary summary(UUID requestId) {
        JsonNode response = restClient.get().uri("/api/v1/download-requests/{id}/result-summary", requestId)
                .header("Authorization", "Bearer " + requiredToken()).retrieve().body(JsonNode.class);
        if (response == null || !requestId.toString().equals(response.path("downloadRequestId").asText())) {
            throw new IllegalStateException("Download Ingestion returned an invalid result summary");
        }
        List<DownloadItem> items = new java.util.ArrayList<>();
        for (JsonNode item : response.path("items")) {
            List<DownloadTag> tags = new java.util.ArrayList<>();
            for (JsonNode tag : item.path("tags")) {
                tags.add(new DownloadTag(tag.path("name").asText(), tag.path("type").asText("topic"),
                        tag.path("confidence").asDouble()));
            }
            items.add(new DownloadItem(item.path("fileName").asText(), item.path("tagStatus").asText("PENDING"),
                    List.copyOf(tags)));
        }
        return new DownloadSummary(requestId, response.path("status").asText(),
                response.path("progressPercent").asInt(0), response.path("progressDownloadedBytes").asLong(0),
                response.path("progressTotalBytes").asLong(0), List.copyOf(items));
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

    /** 下载结果通知快照。 */
    public record DownloadSummary(UUID id, String status, int progressPercent,
                                  long downloadedBytes, long totalBytes, List<DownloadItem> items) {
    }

    /** 单个下载文件的通知快照。 */
    public record DownloadItem(String fileName, String tagStatus, List<DownloadTag> tags) {
    }

    /** 单个自动标签的通知快照。 */
    public record DownloadTag(String name, String type, double confidence) {
    }
}
