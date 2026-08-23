package com.yuyutian.mytools.media.task;

import com.yuyutian.mytools.task.client.TaskSchedulerGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 媒体探测、缩略图与视频分析旁路任务发布器。
 */
@Slf4j
@Component
public class MediaProcessingSidecarPublisher {

    private final TaskSchedulerGateway taskSchedulerGateway;
    private final MediaProcessingSidecarProperties properties;

    /**
     * 创建媒体处理旁路发布器。
     *
     * @param taskSchedulerGateway 任务调度网关
     * @param properties 旁路配置
     */
    public MediaProcessingSidecarPublisher(TaskSchedulerGateway taskSchedulerGateway,
                                           MediaProcessingSidecarProperties properties) {
        this.taskSchedulerGateway = taskSchedulerGateway;
        this.properties = properties;
    }

    /**
     * 异步创建探测和缩略图任务，失败不影响旧链路。
     *
     * @param event 旧链路成功事件
     */
    @Async
    @EventListener
    public void publish(MediaProcessingSidecarRequested event) {
        if (!properties.isEnabled()) {
            return;
        }
        if (event.contentSha256() == null || !event.contentSha256().matches("^[a-fA-F0-9]{64}$")) {
            log.warn("Skipping media processing sidecar tasks because content hash is invalid: fileId={}",
                    event.fileId());
            return;
        }
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("assetId", event.fileId().toString());
        common.put("contentSha256", event.contentSha256().toLowerCase());
        common.put("sourcePath", event.sourcePath());
        common.put("legacyThumbnailPath", event.legacyThumbnailPath());
        create("media_probe", properties.getProbeVersion(), event, common);
        create("media_generate_thumbnail", properties.getThumbnailVersion(), event, common);
        if (event.mimeType() != null && event.mimeType().startsWith("video/")) {
            create("media_analyze_video", properties.getVideoAnalysisVersion(), event, common);
        }
    }

    private void create(String taskName, String policyVersion, MediaProcessingSidecarRequested event,
                        Map<String, Object> parameters) {
        try {
            String idempotencyKey = taskName + ":" + event.contentSha256().toLowerCase() + ":" + policyVersion;
            taskSchedulerGateway.create(taskName, idempotencyKey, "MEDIA_FILE", event.fileId().toString(),
                    properties.getPriority(), parameters);
            log.info("Media processing sidecar task created: taskName={}, fileId={}", taskName, event.fileId());
        } catch (RuntimeException exception) {
            log.warn("Media processing sidecar task creation failed: taskName={}, fileId={}, error={}",
                    taskName, event.fileId(), exception.getMessage());
        }
    }
}
