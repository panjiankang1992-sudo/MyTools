package com.yuyutian.mytools.reader.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 旧书源发现旁路发布器。
 */
@Slf4j
@Component
public class ReaderDiscoverySidecarPublisher {
    private final ReaderDiscoverySidecarClient client;
    private final ReaderDiscoverySidecarProperties properties;

    /**
     * 创建书源发现旁路发布器。
     *
     * @param client Reader Service 客户端
     * @param properties 旁路配置
     */
    public ReaderDiscoverySidecarPublisher(ReaderDiscoverySidecarClient client,
                                           ReaderDiscoverySidecarProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 异步创建新发现任务，失败不影响旧发现。
     *
     * @param event 旧发现事件
     */
    @Async
    @EventListener
    public void publish(ReaderDiscoverySidecarRequested event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ReaderDiscoverySidecarClient.DiscoveryAccepted accepted = client.create(event);
            log.info("Reader discovery sidecar created: legacyTaskId={}, requestId={}, taskId={}",
                    event.legacyTaskId(), accepted.id(), accepted.taskId());
        } catch (RuntimeException exception) {
            log.warn("Reader discovery sidecar creation failed: legacyTaskId={}, error={}",
                    event.legacyTaskId(), exception.getMessage());
        }
    }
}
