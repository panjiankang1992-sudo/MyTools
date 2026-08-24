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
    private final LegacyMediaAnalysisTargetClient targetClient;

    /**
     * 创建媒体处理旁路发布器。
     *
     * @param taskSchedulerGateway 任务调度网关
     * @param properties 旁路配置
     * @param targetClient 旧媒体映射客户端
     */
    public MediaProcessingSidecarPublisher(TaskSchedulerGateway taskSchedulerGateway,
                                           MediaProcessingSidecarProperties properties,
                                           LegacyMediaAnalysisTargetClient targetClient) {
        this.taskSchedulerGateway = taskSchedulerGateway;
        this.properties = properties;
        this.targetClient = targetClient;
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
        if (event.mimeType() == null || !event.mimeType().startsWith("video/")) {
            return;
        }
        if (properties.getExecutorNode() == null || properties.getExecutorNode().isBlank()) {
            log.warn("Skipping media analysis sidecar because executor node is not configured: fileId={}",
                    event.fileId());
            return;
        }
        try {
            LegacyMediaAnalysisTargetClient.AnalysisTarget target = targetClient.resolve(event.fileId());
            if (!target.contentSha256().equalsIgnoreCase(event.contentSha256())
                    || !target.mimeType().startsWith("video/")) {
                log.warn("Skipping media analysis sidecar because migrated identity changed: fileId={}",
                        event.fileId());
                return;
            }
            createAnalysis(event, target);
        } catch (RuntimeException exception) {
            // 映射未迁移或新服务不可用时，旧缩略图与分析链路仍保持权威。
            log.warn("Media analysis sidecar target resolution failed: fileId={}, error={}",
                    event.fileId(), exception.getMessage());
        }
    }

    private void createAnalysis(MediaProcessingSidecarRequested event,
                                LegacyMediaAnalysisTargetClient.AnalysisTarget target) {
        String policyVersion = properties.getVideoAnalysisVersion();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("mediaItemId", target.mediaItemId().toString());
        parameters.put("assetRegistryId", target.assetRegistryId().toString());
        parameters.put("assetId", target.assetRegistryId().toString());
        parameters.put("analysisVersion", policyVersion);
        parameters.put("sourcePath", event.sourcePath());
        parameters.put("contentSha256", target.contentSha256().toLowerCase());
        parameters.put("ownerId", target.ownerId());
        parameters.put("filename", target.displayName());
        parameters.put("mimeType", target.mimeType());
        parameters.put("assetMimeType", target.mimeType());
        try {
            String idempotencyKey = "media_analyze_video:" + target.mediaItemId() + ":" + policyVersion;
            taskSchedulerGateway.create("media_analyze_video", idempotencyKey, "MEDIA_ITEM",
                    target.mediaItemId().toString(), properties.getPriority(), parameters,
                    Map.of("executor.node", properties.getExecutorNode()));
            log.info("Media analysis sidecar task created: fileId={}, mediaItemId={}",
                    event.fileId(), target.mediaItemId());
        } catch (RuntimeException exception) {
            log.warn("Media analysis sidecar task creation failed: fileId={}, error={}",
                    event.fileId(), exception.getMessage());
        }
    }
}
