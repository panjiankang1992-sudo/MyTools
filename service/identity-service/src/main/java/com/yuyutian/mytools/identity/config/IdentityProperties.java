package com.yuyutian.mytools.identity.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** Identity 安全配置。 */
@ConfigurationProperties(prefix="identity")
public record IdentityProperties(String internalToken,String jwtSecret,String issuer,long accessSeconds,long refreshSeconds) { }
