package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 技术来源预算独立配置，令牌只能由服务环境注入。 */
@ConfigurationProperties("news.tech")
public record TechSourceProperties(@DefaultValue("") String githubToken,
                                    @DefaultValue("30") int itemLimit,
                                    @DefaultValue("10") int detailLimit,
                                    @DefaultValue("30") int trackedLimit) {
    /** 限制单轮请求量，保护外部配额和采集租约。 */
    public TechSourceProperties {
        if (itemLimit < 1 || itemLimit > 50 || detailLimit < 0 || detailLimit > 10
                || trackedLimit < 0 || trackedLimit > 30) {
            throw new IllegalArgumentException("news.tech limits exceed collection budget");
        }
    }

    /** 脱敏显示配置，避免意外输出 GitHub 凭据。 */
    @Override
    public String toString() {
        return "TechSourceProperties[itemLimit=" + itemLimit + ", detailLimit=" + detailLimit
                + ", trackedLimit=" + trackedLimit + ", githubToken=REDACTED]";
    }
}
