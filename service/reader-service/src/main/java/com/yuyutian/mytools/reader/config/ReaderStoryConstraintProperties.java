package com.yuyutian.mytools.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 新版本正文长度比例上界，下发时封存，已有版本不会随配置变化而改规则。 */
@ConfigurationProperties("reader.story-constraints")
public record ReaderStoryConstraintProperties(@DefaultValue("700") int minimumLengthPermille,
                                               @DefaultValue("2500") int maximumLengthPermille) {
    /** 范围采用整数千分比，避免跨语言浮点舍入差异。 */
    public ReaderStoryConstraintProperties {
        if (minimumLengthPermille < 500 || minimumLengthPermille > 1000
                || maximumLengthPermille < 1000 || maximumLengthPermille > 4000) throw new IllegalArgumentException("Invalid story constraint bounds");
    }
}
