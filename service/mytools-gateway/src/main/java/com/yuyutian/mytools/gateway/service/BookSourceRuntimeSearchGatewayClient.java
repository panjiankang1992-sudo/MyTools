package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 将已通过 Gateway 校验的书源并发搜索请求转发到现有 Reader Runtime。
 */
@Component
public class BookSourceRuntimeSearchGatewayClient {
    private final RestTemplate restTemplate;
    private final GatewayProperties properties;

    /**
     * 创建书源运行时搜索客户端。
     *
     * @param restTemplate HTTP客户端
     * @param properties Gateway配置
     */
    public BookSourceRuntimeSearchGatewayClient(RestTemplate restTemplate, GatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 启动书源并发搜索。
     *
     * @param payload 搜索参数
     * @param authorization 客户端访问令牌
     * @param correlationId 关联标识
     * @return 搜索任务信封
     */
    public Map<String, Object> start(long ownerId, Map<String, Object> payload, String correlationId) {
        return exchange("/internal/v1/reader/source-search", HttpMethod.POST,
                Map.of("ownerId", ownerId, "search", payload), correlationId);
    }

    /**
     * 查询书源并发搜索。
     *
     * @param taskId 任务标识
     * @param offset 结果偏移
     * @param limit 返回上限
     * @param authorization 客户端访问令牌
     * @param correlationId 关联标识
     * @return 搜索任务信封
     */
    public Map<String, Object> find(long ownerId, String taskId, int offset, int limit, String correlationId) {
        return exchange("/internal/v1/reader/source-search/" + taskId + "?ownerId=" + ownerId
                + "&offset=" + offset + "&limit=" + limit, HttpMethod.GET, null, correlationId);
    }

    /**
     * 取消书源并发搜索。
     *
     * @param taskId 任务标识
     * @param authorization 客户端访问令牌
     * @param correlationId 关联标识
     * @return 搜索任务信封
     */
    public Map<String, Object> cancel(long ownerId, String taskId, String correlationId) {
        return exchange("/internal/v1/reader/source-search/" + taskId + "?ownerId=" + ownerId,
                HttpMethod.DELETE, null, correlationId);
    }

    private Map<String, Object> exchange(String path, HttpMethod method, Map<String, Object> payload,
                                         String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(properties.internalToken());
        headers.set("X-Correlation-Id", correlationId);
        var response = restTemplate.exchange(properties.mytoolsUrl().replaceAll("/+$", "") + path, method,
                new HttpEntity<>(payload, headers), new ParameterizedTypeReference<Map<String, Object>>() { });
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Runtime returned an empty response");
        }
        return response.getBody();
    }
}
