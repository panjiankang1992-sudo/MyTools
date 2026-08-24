package com.yuyutian.mytools.reader.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 书源电子书导入旁路发布器。
 */
@Slf4j
@Component
public class ReaderImportSidecarPublisher {
    private final ReaderImportSidecarClient client;
    private final ReaderImportSidecarProperties properties;

    /**
     * 创建电子书导入旁路发布器。
     *
     * @param client Reader Service 客户端
     * @param properties 旁路配置
     */
    public ReaderImportSidecarPublisher(ReaderImportSidecarClient client,
                                        ReaderImportSidecarProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 异步创建新导入任务，失败不影响旧导入。
     *
     * @param event 旧导入事件
     */
    @Async
    @EventListener
    public void publish(ReaderImportSidecarRequested event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            ReaderImportSidecarClient.ImportAccepted accepted = client.create(event);
            log.info("Reader import sidecar created: legacyTaskId={}, requestId={}, taskId={}",
                    event.legacyTaskId(), accepted.id(), accepted.taskId());
        } catch (RuntimeException exception) {
            log.warn("Reader import sidecar creation skipped: legacyTaskId={}, error={}",
                    event.legacyTaskId(), exception.getMessage());
        }
    }
}
