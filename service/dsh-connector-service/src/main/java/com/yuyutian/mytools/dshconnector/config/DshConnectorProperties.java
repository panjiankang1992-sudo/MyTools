package com.yuyutian.mytools.dshconnector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** DSH 回环 RPC 和探测词分析配置。 */
@Component
@ConfigurationProperties(prefix = "dsh-connector.rpc")
public class DshConnectorProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:3080";
    private String workspacePath = "/home/pankang";
    private String agentPreset = "standard";
    private int connectTimeoutSeconds = 5;
    private int requestTimeoutSeconds = 30;
    private int probeTimeoutSeconds = 90;

    /** 返回是否启用。 */ public boolean isEnabled() { return enabled; }
    /** 设置是否启用。 */ public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** 返回基础地址。 */ public String getBaseUrl() { return baseUrl; }
    /** 设置基础地址。 */ public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    /** 返回工作目录。 */ public String getWorkspacePath() { return workspacePath; }
    /** 设置工作目录。 */ public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    /** 返回代理预设。 */ public String getAgentPreset() { return agentPreset; }
    /** 设置代理预设。 */ public void setAgentPreset(String agentPreset) { this.agentPreset = agentPreset; }
    /** 返回连接超时。 */ public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    /** 设置连接超时。 */ public void setConnectTimeoutSeconds(int value) { connectTimeoutSeconds = value; }
    /** 返回请求超时。 */ public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    /** 设置请求超时。 */ public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    /** 返回探测超时。 */ public int getProbeTimeoutSeconds() { return probeTimeoutSeconds; }
    /** 设置探测超时。 */ public void setProbeTimeoutSeconds(int value) { probeTimeoutSeconds = value; }
}
