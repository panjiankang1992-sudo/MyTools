package com.yuyutian.mytools.reader.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 旧书源电子书导入旁路配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.reader-import")
public class ReaderImportSidecarProperties {
    private boolean enabled;
    private String serviceUrl = "http://127.0.0.1:23230";
    private String internalToken = "";
}
