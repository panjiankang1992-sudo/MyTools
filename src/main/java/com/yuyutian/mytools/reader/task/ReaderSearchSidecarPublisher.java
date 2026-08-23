package com.yuyutian.mytools.reader.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 书源搜索旁路任务发布器。
 */
@Slf4j
@Component
public class ReaderSearchSidecarPublisher {

    private final TaskSchedulerGateway taskSchedulerGateway;
    private final ReaderSearchSidecarProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建书源搜索旁路任务发布器。
     *
     * @param taskSchedulerGateway 任务调度网关
     * @param properties 旁路配置
     * @param objectMapper JSON 转换器
     */
    public ReaderSearchSidecarPublisher(TaskSchedulerGateway taskSchedulerGateway,
                                        ReaderSearchSidecarProperties properties, ObjectMapper objectMapper) {
        this.taskSchedulerGateway = taskSchedulerGateway;
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
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userId", event.userId());
        parameters.put("keyword", event.keyword());
        parameters.put("page", event.page());
        parameters.put("mode", event.mode());
        parameters.put("sources", event.sources());
        try {
            String idempotencyKey = "reader_source_search:" + fingerprint(event)
                    + ":" + properties.getPolicyVersion();
            taskSchedulerGateway.create("reader_source_search", idempotencyKey, "READER_SEARCH",
                    event.userId().toString(), properties.getPriority(), parameters);
            log.info("Reader search sidecar task created: userId={}, sourceCount={}",
                    event.userId(), event.sources().size());
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
