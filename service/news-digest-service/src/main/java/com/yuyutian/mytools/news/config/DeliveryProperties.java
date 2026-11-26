package com.yuyutian.mytools.news.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 新闻出站默认关闭，消息凭据只在服务端配置。 */
@ConfigurationProperties("news.delivery")
public record DeliveryProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:23250") String messagingUrl, @DefaultValue("") String messagingToken,
        @DefaultValue("") String readerUrl) {
    /** 启用发送前必须配置凭据和不携带会话令牌的阅读地址。 */
    public DeliveryProperties {
        if (enabled && (messagingToken.length() < 32 || !validReader(readerUrl))) throw new IllegalArgumentException("News delivery configuration is incomplete");
    }
    private static boolean validReader(String value) {
        try {
            var uri = java.net.URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}
