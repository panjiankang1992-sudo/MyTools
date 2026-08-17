package com.yuyutian.mytools.dsh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DSH 后端连接配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "dsh")
public class DshProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:3080";
    private String workspacePath = "/home/pankang";
    private String agentPreset = "standard";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 30;
}
