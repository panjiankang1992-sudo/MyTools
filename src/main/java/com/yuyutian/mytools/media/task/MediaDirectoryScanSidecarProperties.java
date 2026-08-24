package com.yuyutian.mytools.media.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 旧媒体目录扫描旁路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.media-directory-scan")
public class MediaDirectoryScanSidecarProperties {
    private boolean enabled;
    private String mediaLibraryUrl = "http://127.0.0.1:23280";
    private String mediaLibraryToken = "";
    private long ownerId = 1L;
    private String directoryKey = "legacy-media-root";
    private String directoryName = "Legacy Media";
    private boolean analyze;
}
