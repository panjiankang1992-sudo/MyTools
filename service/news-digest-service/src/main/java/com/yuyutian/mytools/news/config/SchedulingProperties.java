package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.util.Set;

/** 自动编排默认关闭；工作令牌与 Scheduler 业务凭据分别配置。 */
@ConfigurationProperties("news.scheduling")
public record SchedulingProperties(boolean enabled, String schedulerUrl, String businessToken, String workerToken) {
    /** 限制固定内部目标，禁止凭据随重定向或明文公网连接传输。 */
    public SchedulingProperties {
        URI uri = URI.create(schedulerUrl);
        boolean secure = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()));
        if (!secure || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Invalid news scheduler URL");
        }
        if (enabled && (businessToken == null || businessToken.length() < 32 || workerToken == null || workerToken.length() < 32)) {
            throw new IllegalArgumentException("News scheduling credentials are required");
        }
    }

    /** 配置对象输出时隐藏凭据。 */
    @Override public String toString() { return "SchedulingProperties[enabled=" + enabled + ", credentials=redacted]"; }
}
