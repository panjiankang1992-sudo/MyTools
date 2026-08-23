package com.yuyutian.mytools.auth.identity;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** Gateway 到 Identity 的迁移配置。 */
@Data @Component @ConfigurationProperties(prefix="migration.identity-validation")
public class IdentityValidationProperties {
 /** 身份验证模式。 */ public enum Mode { LEGACY, DUAL, IDENTITY }
 private Mode mode=Mode.LEGACY;
 private String serviceUrl="http://127.0.0.1:23290";
 private String internalToken="";
}
