package com.yuyutian.mytools.media.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体探测与缩略图旁路任务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.media-processing")
public class MediaProcessingSidecarProperties {
    private boolean enabled;
    private int priority = 30;
    private String videoAnalysisVersion = "media-video-analysis-v1";
    private String mediaLibraryUrl = "http://127.0.0.1:23280";
    private String mediaLibraryToken = "";
    private String executorNode = "";
}
