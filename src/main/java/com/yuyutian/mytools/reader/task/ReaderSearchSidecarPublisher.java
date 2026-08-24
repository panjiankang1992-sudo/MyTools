package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 书源搜索旁路任务发布器。
 */
@Slf4j
@Component
public class ReaderSearchSidecarPublisher {

    private final ReaderSearchSidecarClient client;
    private final ReaderSearchSidecarProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建书源搜索旁路任务发布器。
     *
     * @param client Reader Service 客户端
     * @param properties 旁路配置
     * @param objectMapper JSON 转换器
     */
    public ReaderSearchSidecarPublisher(ReaderSearchSidecarClient client,
                                        ReaderSearchSidecarProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步创建书源搜索旁路任务，失败不影响现有内存任务。
     *
     * @param event 搜索事件
     */
    @Async
    @EventListener
    public void publish(ReaderSearchSidecarRequested event) {
        if (!properties.isEnabled()) {
            return;
        }
        // 新服务尚未实现探测模式的关键词扩展，避免以模糊搜索代替后产生错误结果。
        if ("PROBE".equals(event.mode())) {
            log.info("Reader search sidecar skipped for unsupported mode: userId={}, mode={}",
                    event.userId(), event.mode());
            return;
        }
        try {
            String idempotencyKey = "legacy-shadow:" + fingerprint(event)
                    + ":" + properties.getPolicyVersion();
            ReaderSearchSidecarClient.SearchAccepted accepted = client.create(event, idempotencyKey);
            log.info("Reader search sidecar request created: userId={}, requestId={}, sourceCount={}",
                    event.userId(), accepted.id(), event.sources().size());
        } catch (RuntimeException exception) {
            log.warn("Reader search sidecar task creation failed: userId={}, error={}",
                    event.userId(), exception.getMessage());
        }
    }

    private String fingerprint(ReaderSearchSidecarRequested event) {
        try {
            byte[] value = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Reader search event cannot be fingerprinted", exception);
        }
    }
}
