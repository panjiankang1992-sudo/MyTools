package com.yuyutian.mytools.reader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阅读书源规则执行器配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mytools.reader-runtime")
public class ReaderRuntimeProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:23120";
    private String secureKey = "";
    private int connectTimeoutSeconds = 3;
    private int requestTimeoutSeconds = 25;
    private int searchConcurrency = 20;
    private String chapterCacheDir = "/opt/extend/resource/.mytools/reader-chapters";
    private int chapterCacheTtlHours = 168;
    private int chapterCacheMaxEntries = 20000;
}
