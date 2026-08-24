package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.AppCatalogGatewayProperties;
import com.yuyutian.mytools.gateway.model.AppCatalogGatewayModels.CatalogView;
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
 * 应用目录内部客户端。
 */
@Component
public class AppCatalogGatewayClient {
    private final RestTemplate restTemplate;
    private final AppCatalogGatewayProperties properties;

    /**
     * 创建应用目录客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties Gateway 配置
     */
    public AppCatalogGatewayClient(RestTemplate restTemplate, AppCatalogGatewayProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 查询当前所有者的应用目录。
     *
     * @param correlationId 关联标识
     * @return 目录摘要
     */
    public List<CatalogView> list(String correlationId) {
        URI url = UriComponentsBuilder.fromHttpUrl(root() + "/internal/v1/catalog/entries")
                .build().encode().toUri();
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(correlationId),
                new ParameterizedTypeReference<List<CatalogView>>() { });
        return response.getBody() == null ? List.of() : response.getBody();
    }

    private HttpEntity<Void> entity(String correlationId) {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new GatewayUnauthorizedException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.token());
        headers.set("X-Correlation-Id", correlationId);
        return new HttpEntity<>(headers);
    }

    private String root() {
        return properties.url().replaceAll("/+$", "");
    }
}
