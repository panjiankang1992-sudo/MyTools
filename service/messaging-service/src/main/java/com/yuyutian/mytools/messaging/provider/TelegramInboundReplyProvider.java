package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Telegram 原会话回复适配器。
 */
@Component
public class TelegramInboundReplyProvider implements InboundReplyProvider {

    private final RestClient restClient;
    private final MessagingProperties properties;

    /**
     * 创建 Telegram 回复适配器。
     *
     * @param builder HTTP 客户端构建器
     * @param properties 消息服务配置
     */
    public TelegramInboundReplyProvider(RestClient.Builder builder, MessagingProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.telegramConnectorUrl()).build();
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    /** {@inheritDoc} */
    @Override
    public void reply(InboundMessageView message, String idempotencyKey, String body) {
        String[] identity = message.externalMessageId().split(":", 3);
        if (identity.length != 3 || identity[1].isBlank() || identity[2].isBlank()) {
            throw new IllegalStateException("Telegram message identity is invalid");
        }
        if (properties.telegramConnectorToken() == null || properties.telegramConnectorToken().isBlank()) {
            throw new IllegalStateException("Telegram Connector token is missing");
        }
        restClient.post().uri("/internal/v1/messages/text")
                .header("Authorization", "Bearer " + properties.telegramConnectorToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("chatId", identity[1], "messageId", Long.parseLong(identity[2]),
                        "text", body, "idempotencyKey", idempotencyKey))
                .retrieve().toBodilessEntity();
    }
}
