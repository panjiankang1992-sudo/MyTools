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
    private int priority = 40;
    private String policyVersion = "reader-search-v2";
}
