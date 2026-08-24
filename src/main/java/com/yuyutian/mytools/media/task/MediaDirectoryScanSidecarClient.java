package com.yuyutian.mytools.media.task;

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
 * 通过 Media Library 创建持久化目录扫描操作。
 */
@Component
public class MediaDirectoryScanSidecarClient {
    private final RestTemplate restTemplate;
    private final MediaDirectoryScanSidecarProperties properties;

    /**
     * 创建媒体目录扫描旁路客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public MediaDirectoryScanSidecarClient(RestTemplate restTemplate,
                                           MediaDirectoryScanSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 幂等创建目录扫描操作。
     *
     * @param rootPath 扫描根目录
     * @param idempotencyKey 幂等键
     * @return 已创建操作
     */
    public ScanAccepted create(String rootPath, String idempotencyKey) {
        if (properties.getMediaLibraryToken() == null || properties.getMediaLibraryToken().isBlank()) {
            throw new IllegalStateException("Media Library token is missing");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("idempotencyKey", idempotencyKey);
        request.put("rootPath", rootPath);
        request.put("directoryKey", properties.getDirectoryKey());
        request.put("directoryName", properties.getDirectoryName());
        request.put("analyze", properties.isAnalyze());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getMediaLibraryToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String root = properties.getMediaLibraryUrl().replaceAll("/+$", "");
        String url = UriComponentsBuilder.fromHttpUrl(
                        root + "/internal/v1/media/operations/directory-scans")
                .queryParam("ownerId", properties.getOwnerId())
                .build().toUriString();
        var response = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(request, headers), ScanAccepted.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Media Library returned an empty scan response");
        }
        return response.getBody();
    }

    /**
     * Media Library 扫描操作的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScanAccepted(UUID id, UUID taskInstanceId, String status) {
    }
}
