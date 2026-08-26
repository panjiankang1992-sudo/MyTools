package com.yuyutian.mytools.messaging.provider;

import com.yuyutian.mytools.messaging.config.MessagingProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * QQ 原会话回复适配器。
 */
@Component
public class QqInboundReplyProvider implements InboundReplyProvider {

    private final RestClient restClient;
    private final MessagingProperties properties;

    /**
     * 创建 QQ 回复适配器。
     *
     * @param builder HTTP 客户端构建器
     * @param properties 消息服务配置
     */
    public QqInboundReplyProvider(RestClient.Builder builder, MessagingProperties properties) {
        this.restClient = builder.clone().baseUrl(properties.qqConnectorUrl()).build();
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public ChannelType channelType() {
        return ChannelType.QQ;
    }

    /** {@inheritDoc} */
    @Override
    public void reply(InboundMessageView message, String idempotencyKey, String body) {
        String[] identity = message.externalMessageId().split(":", 3);
        if (identity.length != 3 || identity[2].isBlank()) {
            throw new IllegalStateException("QQ message identity is invalid");
        }
        if (properties.qqConnectorToken() == null || properties.qqConnectorToken().isBlank()) {
            throw new IllegalStateException("QQ Connector token is missing");
        }
        restClient.post().uri("/internal/v1/messages/text")
                .header("Authorization", "Bearer " + properties.qqConnectorToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sender", message.sender(), "messageId", identity[2],
                        "text", body, "sequence", 2, "idempotencyKey", idempotencyKey))
                .retrieve().toBodilessEntity();
    }
}
