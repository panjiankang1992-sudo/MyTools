package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.DshGatewayProperties;
import com.yuyutian.mytools.gateway.model.DshGatewayModels.BindingView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * DSH Connector 内部客户端。
 */
@Component
public class DshGatewayClient {
    private final RestTemplate restTemplate;
    private final DshGatewayProperties properties;

    /**
     * 创建 DSH 客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties Gateway 配置
     */
    public DshGatewayClient(RestTemplate restTemplate, DshGatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 查询当前所有者的会话绑定。
     *
     * @param ownerId 所有者
     * @param correlationId 关联标识
     * @return 会话绑定
     */
    public List<BindingView> list(long ownerId, String correlationId) {
        URI url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/dsh/sessions")
                .queryParam("ownerId", ownerId).build().encode().toUri();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId),
                new ParameterizedTypeReference<List<BindingView>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    /**
     * 归档当前所有者的会话。
     *
     * @param sessionId 外部会话标识
     * @param ownerId 所有者
     * @param correlationId 关联标识
     */
    public void archive(String sessionId, long ownerId, String correlationId) {
        URI url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/dsh/sessions/" + sessionId)
                .queryParam("ownerId", ownerId).build().encode().toUri();
        restTemplate.exchange(url, HttpMethod.DELETE, entity(null, correlationId), Void.class);
    }

    private <T> HttpEntity<T> entity(T body, String correlationId) {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.token());
        headers.set("X-Correlation-Id", correlationId);
        return new HttpEntity<>(body, headers);
    }

    private String root() {
        return properties.url().replaceAll("/+$", "");
    }
}
