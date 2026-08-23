package com.yuyutian.mytools.identity.config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Identity 组件配置。 */
@Configuration @EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {
 /** 创建密码编码器。 @return 编码器 */ @Bean public BCryptPasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
 /** 创建事务模板。 @param manager 管理器 @return 模板 */ @Bean public TransactionTemplate transactionTemplate(PlatformTransactionManager manager){return new TransactionTemplate(manager);}
}
