package com.yuyutian.mytools.messaging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 新版投递使用独立的客户端、执行器和绑定管理凭据，默认关闭发送。 */
@Component
@ConfigurationProperties("messaging.outbound")
public class OutboundProperties {
    private boolean enabled;
    private String clientToken = "";
    private String workerToken = "";
    private String adminToken = "";
    private String encryptionKey = "";
    /** 返回投递开关。 */
    public boolean isEnabled() { return enabled; }
    /** 设置投递开关。 */
    public void setEnabled(boolean value) { enabled = value; }
    /** 返回业务客户端凭据。 */
    public String getClientToken() { return clientToken; }
    /** 设置业务客户端凭据。 */
    public void setClientToken(String value) { clientToken = value; }
    /** 返回执行器凭据。 */
    public String getWorkerToken() { return workerToken; }
    /** 设置执行器凭据。 */
    public void setWorkerToken(String value) { workerToken = value; }
    /** 返回绑定管理凭据。 */
    public String getAdminToken() { return adminToken; }
    /** 设置绑定管理凭据。 */
    public void setAdminToken(String value) { adminToken = value; }
    /** 返回仅在服务端使用的加密密钥。 */
    public String getEncryptionKey() { return encryptionKey; }
    /** 设置加密密钥。 */
    public void setEncryptionKey(String value) { encryptionKey = value; }
}
