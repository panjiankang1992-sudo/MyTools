package com.yuyutian.mytools.automation.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * 下载接入服务业务请求客户端。
 */
public class DownloadIngestionClient {

    private final RestClient restClient;

    /**
     * 创建下载接入客户端。
     */
    public DownloadIngestionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 幂等创建一个消息 URL 下载请求。
     *
     * @return 下载业务请求标识
     */
    public String create(UUID messageId, UUID ruleId, int index, String requestKind,
                         String url, String fileName) {
        String idempotencyKey = "automation:" + messageId + ":" + ruleId + ":" + index;
        Map<String, Object> payload = Map.of(
                "idempotencyKey", idempotencyKey,
                "sourceType", "MESSAGE",
                "sourceKey", messageId.toString(),
                "requestKind", requestKind,
                "parameters", Map.of("itemId", messageId + "-" + index, "url", url, "fileName", fileName));
        JsonNode response = restClient.post().uri("/api/v1/download-requests")
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(JsonNode.class);
        if (response == null || response.path("id").isMissingNode()) {
            throw new IllegalStateException("Download Ingestion returned an invalid response");
        }
        return response.path("id").asText();
    }
}
