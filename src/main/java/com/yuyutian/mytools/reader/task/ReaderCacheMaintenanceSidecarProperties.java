package com.yuyutian.mytools.reader.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reader 章节缓存维护旁路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.reader-cache-maintenance")
public class ReaderCacheMaintenanceSidecarProperties {
    private boolean enabled;
    private String serviceUrl = "http://127.0.0.1:23230";
    private String internalToken = "";
    private int batchSize = 500;
}
