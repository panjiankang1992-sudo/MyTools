package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 Reader Service 创建持久化书源发现。
 */
@Component
public class ReaderDiscoverySidecarClient {
    private final RestTemplate restTemplate;
    private final ReaderDiscoverySidecarProperties properties;

    /**
     * 创建书源发现旁路客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public ReaderDiscoverySidecarClient(RestTemplate restTemplate,
                                        ReaderDiscoverySidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 幂等创建书源发现任务。
     *
     * @param event 旧发现事件
     * @return 新发现任务摘要
     */
    public DiscoveryAccepted create(ReaderDiscoverySidecarRequested event) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("Reader Service token is missing");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ownerId", event.ownerId());
        request.put("idempotencyKey", "legacy-source-discovery:" + event.legacyTaskId());
        request.put("url", event.url());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getInternalToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String root = properties.getServiceUrl().replaceAll("/+$", "");
        var response = restTemplate.exchange(root + "/api/v1/source-discoveries", HttpMethod.POST,
                new HttpEntity<>(request, headers), DiscoveryAccepted.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty discovery response");
        }
        return response.getBody();
    }

    /**
     * Reader Service 发现结果的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscoveryAccepted(UUID id, UUID taskId, String status) {
    }
}
