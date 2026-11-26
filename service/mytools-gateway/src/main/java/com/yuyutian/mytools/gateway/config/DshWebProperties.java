package com.yuyutian.mytools.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Set;

/** DSH 网页接入配置，默认关闭并拒绝未列入名单的账号。 */
@Component
@ConfigurationProperties(prefix = "gateway.dsh-web")
public class DshWebProperties {
    private boolean enabled;
    private Set<String> allowedUsernames = Set.of();
    private String cookieFile = "";
    private String proxySecret = "";
    /** 返回开关。 */
    public boolean isEnabled() { return enabled; }
    /** 设置开关。 */
    public void setEnabled(boolean value) { enabled = value; }
    /** 返回授权账号。 */
    public Set<String> getAllowedUsernames() { return allowedUsernames; }
    /** 设置授权账号。 */
    public void setAllowedUsernames(Set<String> value) { allowedUsernames = Set.copyOf(value); }
    /** 返回上游凭据文件路径。 */
    public String getCookieFile() { return cookieFile; }
    /** 设置上游凭据文件路径。 */
    public void setCookieFile(String value) { cookieFile = value; }
    /** 返回代理认证密钥。 */
    public String getProxySecret() { return proxySecret; }
    /** 设置代理认证密钥。 */
    public void setProxySecret(String value) { proxySecret = value; }
}
