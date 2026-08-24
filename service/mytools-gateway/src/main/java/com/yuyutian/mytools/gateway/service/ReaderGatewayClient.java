package com.yuyutian.mytools.gateway.service;

import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CatalogView;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.CreateImport;
import com.yuyutian.mytools.gateway.model.EbookImportGatewayModels.ImportView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.yuyutian.mytools.gateway.model.ReaderSearchGatewayModels.*;

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

    /**
     * 创建书源搜索任务。
     *
     * @param ownerId 所有者
     * @param request 请求
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView createSearch(long ownerId, CreateSearch request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("keyword", request.keyword());
        payload.put("mode", request.mode());
        payload.put("page", request.page());
        payload.put("sources", request.sources());
        return exchangeSearch(root() + "/api/v1/book-searches", HttpMethod.POST, payload, correlationId);
    }

    /**
     * 查询书源搜索。
     *
     * @param ownerId 所有者
     * @param id 搜索
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView search(long ownerId, UUID id, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/book-searches/" + id)
                .queryParam("ownerId", ownerId).toUriString();
        return exchangeSearch(url, HttpMethod.GET, null, correlationId);
    }

    /**
     * 取消书源搜索。
     *
     * @param ownerId 所有者
     * @param id 搜索
     * @param correlationId 关联标识
     * @return 搜索
     */
    public SearchView cancelSearch(long ownerId, UUID id, String correlationId) {
        String url = UriComponentsBuilder.fromHttpUrl(root() + "/api/v1/book-searches/" + id + "/cancel")
                .queryParam("ownerId", ownerId).toUriString();
        return exchangeSearch(url, HttpMethod.POST, null, correlationId);
    }

    /**
     * 创建电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param request 创建请求
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView createImport(long ownerId, CreateImport request, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ownerId", ownerId);
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.put("sourceId", request.sourceId());
        payload.put("bookUrl", request.bookUrl());
        payload.put("title", request.title());
        payload.put("author", request.author());
        return exchangeImport(root() + "/api/v1/ebook-imports", HttpMethod.POST, payload, correlationId);
    }

    /**
     * 查询电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView importView(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id, ownerId);
        return exchangeImport(url, HttpMethod.GET, null, correlationId);
    }

    /**
     * 取消电子书导入任务。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 导入视图
     */
    public ImportView cancelImport(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id + "/cancel", ownerId);
        return exchangeImport(url, HttpMethod.POST, null, correlationId);
    }

    /**
     * 查询电子书目录。
     *
     * @param ownerId 所有者标识
     * @param id 导入标识
     * @param correlationId 关联标识
     * @return 目录视图
     */
    public CatalogView importCatalog(long ownerId, UUID id, String correlationId) {
        String url = ownerUrl(root() + "/api/v1/ebook-imports/" + id + "/catalog", ownerId);
        var response = restTemplate.exchange(url, HttpMethod.GET, entity(null, correlationId), CatalogView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    private SearchView exchangeSearch(String url, HttpMethod method, Object body, String correlationId) {
        var response = restTemplate.exchange(url, method, entity(body, correlationId), SearchView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    private ImportView exchangeImport(String url, HttpMethod method, Object body, String correlationId) {
        var response = restTemplate.exchange(url, method, entity(body, correlationId), ImportView.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty response");
        }
        return response.getBody();
    }

    private String ownerUrl(String url, long ownerId) {
        return UriComponentsBuilder.fromHttpUrl(url).queryParam("ownerId", ownerId).toUriString();
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
