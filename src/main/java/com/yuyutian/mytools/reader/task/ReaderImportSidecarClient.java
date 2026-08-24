package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 Reader Service 创建持久化电子书导入。
 */
@Component
public class ReaderImportSidecarClient {
    private final RestTemplate restTemplate;
    private final ReaderImportSidecarProperties properties;

    /**
     * 创建电子书导入旁路客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public ReaderImportSidecarClient(RestTemplate restTemplate, ReaderImportSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 解析已迁移书源并创建导入任务。
     *
     * @param event 旧导入事件
     * @return 新导入摘要
     */
    public ImportAccepted create(ReaderImportSidecarRequested event) {
        HttpHeaders headers = authorizedHeaders();
        String root = properties.getServiceUrl().replaceAll("/+$", "");
        String resolveUrl = UriComponentsBuilder.fromHttpUrl(root + "/api/internal/v1/book-sources/resolve")
                .queryParam("ownerId", event.ownerId())
                .queryParam("sourceUrl", event.sourceUrl())
                .build().encode().toUriString();
        var sourceResponse = restTemplate.exchange(resolveUrl, HttpMethod.GET,
                new HttpEntity<>(headers), SourceReference.class);
        SourceReference source = sourceResponse.getBody();
        if (source == null || source.id() == null) {
            throw new IllegalStateException("Reader Service returned an empty source response");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ownerId", event.ownerId());
        request.put("idempotencyKey", "legacy-source-import:" + event.legacyTaskId());
        request.put("sourceId", source.id());
        request.put("bookUrl", event.bookUrl());
        request.put("title", event.title());
        request.put("author", event.author() == null ? "" : event.author());
        headers.setContentType(MediaType.APPLICATION_JSON);
        var importResponse = restTemplate.exchange(root + "/api/v1/ebook-imports", HttpMethod.POST,
                new HttpEntity<>(request, headers), ImportAccepted.class);
        if (importResponse.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty import response");
        }
        return importResponse.getBody();
    }

    private HttpHeaders authorizedHeaders() {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("Reader Service token is missing");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getInternalToken());
        return headers;
    }

    /**
     * 已迁移书源引用的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceReference(UUID id, String sourceUrl, int version) {
    }

    /**
     * Reader Service 导入结果的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportAccepted(UUID id, UUID taskId, String status) {
    }
}
