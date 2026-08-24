package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 Reader Service 创建章节缓存维护任务。
 */
@Component
public class ReaderCacheMaintenanceSidecarClient {
    private final RestTemplate restTemplate;
    private final ReaderCacheMaintenanceSidecarProperties properties;

    /**
     * 创建章节缓存维护客户端。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路配置
     */
    public ReaderCacheMaintenanceSidecarClient(RestTemplate restTemplate,
                                               ReaderCacheMaintenanceSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 幂等创建过期缓存清理任务。
     *
     * @param cutoffAt 清理截止时间
     * @param idempotencyKey 幂等键
     * @return 维护任务摘要
     */
    public MaintenanceAccepted create(Instant cutoffAt, String idempotencyKey) {
        if (properties.getInternalToken() == null || properties.getInternalToken().isBlank()) {
            throw new IllegalStateException("Reader Service token is missing");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("idempotencyKey", idempotencyKey);
        request.put("maintenanceType", "EXPIRED");
        request.put("cutoffAt", cutoffAt.toString());
        request.put("batchSize", properties.getBatchSize());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getInternalToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        String root = properties.getServiceUrl().replaceAll("/+$", "");
        var response = restTemplate.exchange(root + "/api/internal/v1/cache-maintenance", HttpMethod.POST,
                new HttpEntity<>(request, headers), MaintenanceAccepted.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Reader Service returned an empty maintenance response");
        }
        return response.getBody();
    }

    /**
     * Reader Service 缓存维护结果的最小投影。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaintenanceAccepted(UUID id, UUID taskId, String status) {
    }
}
