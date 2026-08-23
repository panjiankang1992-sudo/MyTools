package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 只转发 Gateway 构造载荷的 Reader 内部客户端。
 */
@Component
public class ReaderGatewayClient {

    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建 Reader Gateway 客户端。
     */
    public ReaderGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 查询当前主体的一类 Reader 状态。
     */
    public List<Map<String, Object>> list(String resource, long ownerId, boolean includeDeleted,
                                          String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/reader-state/" + resource)
                .queryParam("ownerId", ownerId).queryParam("includeDeleted", includeDeleted)
                .toUriString();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId),
                new ParameterizedTypeReference<List<Map<String, Object>>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /**
     * 写入 Gateway 已绑定 owner 的 Reader 状态。
     */
    public Map<String, Object> save(String resource, Map<String, Object> payload, String correlationId) {
        var response = restTemplate.exchange(root() + "/api/v1/reader-state/" + resource,
                HttpMethod.POST, entity(payload, correlationId),
                new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    private HttpEntity<?> entity(Object body, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (properties.readerToken() == null || properties.readerToken().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        headers.setBearerAuth(properties.readerToken());
        headers.set("X-Correlation-Id", correlationId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String root() {
        return properties.readerUrl().replaceAll("/+$", "");
    }
}
