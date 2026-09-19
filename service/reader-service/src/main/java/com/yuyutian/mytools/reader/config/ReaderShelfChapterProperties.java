package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 书架章节准备的独立门禁，不随已有书源阅读接口自动启用。 */
@ConfigurationProperties(prefix = "reader.shelf-chapters")
public record ReaderShelfChapterProperties(@DefaultValue("false") boolean enabled,
                                           @DefaultValue("false") boolean runtimeEgressVerified,
                                           @DefaultValue("") String locatorKeyringFile,
                                           @DefaultValue("180") int claimSeconds,
                                           @DefaultValue("3") int maximumAttempts,
                                           @DefaultValue("2") int maximumPreparingPerOwner) {
    /** 限制任务恢复预算，密钥内容不得经配置属性注入或输出。 */
    public ReaderShelfChapterProperties {
        if (claimSeconds < 30 || claimSeconds > 600 || maximumAttempts < 1 || maximumAttempts > 10
                || maximumPreparingPerOwner < 1 || maximumPreparingPerOwner > 10) {
            throw new IllegalArgumentException("Invalid shelf chapter preparation limits");
        }
    }
}
