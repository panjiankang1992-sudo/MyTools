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
 * 通过 Reader Service 创建持久化书源搜索。
 */
@Component
public class ReaderSearchSidecarClient {
    private final RestTemplate restTemplate;
    private final ReaderSearchSidecarProperties properties;

    /**
     * 创建书源搜索旁路客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public ReaderSearchSidecarClient(RestTemplate restTemplate, ReaderSearchSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 幂等创建领域搜索请求。
     *
     * @param event 旧搜索快照
     * @param idempotencyKey 幂等键
     * @return 新领域搜索摘要
     */
    public SearchAccepted create(ReaderSearchSidecarRequested event, String idempotencyKey) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("Reader Service token is missing");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ownerId", event.userId());
        request.put("idempotencyKey", idempotencyKey);
        request.put("keyword", event.keyword());
        request.put("mode", event.mode());
        request.put("page", event.page());
        request.put("searchTerms", event.searchTerms());
        request.put("sources", event.sources());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getInternalToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String root = properties.getServiceUrl().replaceAll("/+$", "");
        var response = restTemplate.exchange(root + "/api/v1/book-searches", HttpMethod.POST,
                new HttpEntity<>(request, headers), SearchAccepted.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty search response");
        }
        return response.getBody();
    }

    /**
     * Reader Service 创建结果的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchAccepted(UUID id, String status) {
    }
}
