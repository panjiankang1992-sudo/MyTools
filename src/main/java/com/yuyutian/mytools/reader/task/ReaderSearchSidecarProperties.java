package com.yuyutian.mytools.reader.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 书源搜索旁路任务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "migration.tasks.reader-search")
public class ReaderSearchSidecarProperties {
    private boolean enabled;
    private String policyVersion = "reader-search-v4";
    private String serviceUrl = "http://127.0.0.1:23230";
    private String internalToken = "";
}
