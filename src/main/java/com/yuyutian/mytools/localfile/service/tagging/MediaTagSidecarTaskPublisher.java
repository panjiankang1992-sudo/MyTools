package com.yuyutian.mytools.localfile.service.tagging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将旧标签成功事件异步发布为任务调度器旁路任务。
 */
@Slf4j
@Component
public class MediaTagSidecarTaskPublisher {

    private final RestTemplate restTemplate;
    private final MediaTagSidecarProperties properties;

    /**
     * 创建旁路标签任务发布器。
     *
     * @param restTemplate HTTP 客户端
     * @param properties 旁路任务配置
     */
    public MediaTagSidecarTaskPublisher(RestTemplate restTemplate, MediaTagSidecarProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 异步创建旁路任务，任何失败都只记录日志，不改变旧标签事务结果。
     *
     * @param event 标签成功事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(MediaTagSidecarTaskRequested event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (event.contentSha256() == null || !event.contentSha256().matches("^[a-fA-F0-9]{64}$")) {
            log.warn("Skipping media tag sidecar task because content hash is invalid: fileId={}", event.fileId());
            return;
        }
        try {
            restTemplate.postForEntity(normalizedSchedulerUrl() + "/api/v1/task-instances",
                    createRequest(event), Map.class);
            log.info("Media tag sidecar task created: fileId={}, policyVersion={}",
                    event.fileId(), properties.getPolicyVersion());
        } catch (RestClientException exception) {
            // 旁路不可用时旧标签仍是权威结果，禁止向旧调用链传播异常。
            log.warn("Media tag sidecar task creation failed: fileId={}, error={}",
                    event.fileId(), exception.getMessage());
        }
    }

    private Map<String, Object> createRequest(MediaTagSidecarTaskRequested event) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sourcePath", event.sourcePath());
        parameters.put("filename", event.filename());
        parameters.put("mimeType", event.mimeType());
        parameters.put("contentSha256", event.contentSha256().toLowerCase());
        parameters.put("policyVersion", properties.getPolicyVersion());
        parameters.put("serviceUrl", properties.getServiceUrl());
        parameters.put("model", properties.getModel());
        if (event.thumbnailPath() != null && !event.thumbnailPath().isBlank()) {
            parameters.put("thumbnailPath", event.thumbnailPath());
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("taskName", properties.getTaskName());
        request.put("idempotencyKey", properties.getTaskName() + ":" + event.contentSha256().toLowerCase()
                + ":" + properties.getPolicyVersion());
        request.put("businessType", "MEDIA_FILE");
        request.put("businessId", event.fileId().toString());
        request.put("parentTaskInstanceId", null);
        request.put("priority", properties.getPriority());
        request.put("parameters", parameters);
        return request;
    }

    private String normalizedSchedulerUrl() {
        String value = properties.getSchedulerUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
