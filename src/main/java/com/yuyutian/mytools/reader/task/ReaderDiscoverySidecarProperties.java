package com.yuyutian.mytools.reader.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 旧书源发现旁路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.reader-discovery")
public class ReaderDiscoverySidecarProperties {
    private boolean enabled;
    private String serviceUrl = "http://127.0.0.1:23230";
    private String internalToken = "";
}
