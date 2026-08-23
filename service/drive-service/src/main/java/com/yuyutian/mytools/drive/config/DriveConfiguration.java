package com.yuyutian.mytools.drive.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Drive 服务配置。 */
@Configuration
public class DriveConfiguration {
    /** 创建事务模板。 @param manager 事务管理器 @return 事务模板 */
    @Bean public TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
    /** 创建内部令牌配置。 @param token 内部令牌 @return 令牌 */
    @Bean public InternalToken internalToken(@Value("${drive.internal-token:}") String token) {
        return new InternalToken(token);
    }
    /** 内部 API 令牌。 */
    public record InternalToken(String value) { }
}
