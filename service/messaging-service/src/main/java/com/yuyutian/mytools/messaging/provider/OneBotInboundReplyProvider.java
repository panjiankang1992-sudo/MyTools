package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * OneBot 原会话回复适配器。
 */
@Component
public class OneBotInboundReplyProvider implements InboundReplyProvider {

    private final RestClient restClient;
    private final MessagingProperties properties;

    /**
     * 创建 OneBot 回复适配器。
     *
     * @param builder HTTP 客户端构建器
     * @param properties 消息服务配置
     */
    public OneBotInboundReplyProvider(RestClient.Builder builder, MessagingProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.providerResolverUrl()).build();
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public ChannelType channelType() {
        return ChannelType.ONEBOT;
    }

    /** {@inheritDoc} */
    @Override
    public void reply(InboundMessageView message, String idempotencyKey, String body) {
        String[] identity = message.externalMessageId().split(":", 6);
        if (identity.length != 6 || identity[0].isBlank() || identity[3].isBlank()
                || identity[4].isBlank() || identity[5].isBlank()) {
            throw new IllegalStateException("OneBot message identity is invalid");
        }
        if (properties.providerResolverToken() == null || properties.providerResolverToken().isBlank()) {
            throw new IllegalStateException("OneBot Connector token is missing");
        }
        restClient.post().uri("/internal/v1/messages/text")
                .header("Authorization", "Bearer " + properties.providerResolverToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountKey", identity[0], "messageType", identity[3],
                        "targetId", identity[4], "messageId", identity[5], "text", body,
                        "idempotencyKey", idempotencyKey))
                .retrieve().toBodilessEntity();
    }
}
