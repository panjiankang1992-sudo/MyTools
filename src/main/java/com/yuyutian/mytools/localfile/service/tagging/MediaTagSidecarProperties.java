package com.yuyutian.mytools.localfile.service.tagging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 媒体标签旁路任务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.media-tags")
public class MediaTagSidecarProperties {
    private boolean enabled;
    private String schedulerUrl = "http://127.0.0.1:23210";
    private String taskName = "media_generate_tags";
    private String policyVersion = "media-tags-v1";
    private int priority = 40;
    private String serviceUrl = "http://127.0.0.1:11434";
    private String model = "huihui_ai/qwen3-vl-abliterated:4b";
}
