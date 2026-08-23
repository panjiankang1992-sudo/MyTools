package com.yuyutian.mytools.automation.service;

import com.yuyutian.mytools.automation.model.InboundMessage;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * 标准入站消息只读客户端。
 */
public class MessagingClient {

    private final RestClient restClient;
    private final String token;

    /**
     * 创建标准消息客户端。
     */
    public MessagingClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    /**
     * 使用消息标识读取完整标准消息。
     */
    public InboundMessage get(UUID messageId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Messaging internal token is missing");
        }
        InboundMessage message = restClient.get().uri("/internal/v1/inbound-messages/{id}", messageId)
                .header("Authorization", "Bearer " + token).retrieve().body(InboundMessage.class);
        if (message == null || !messageId.equals(message.id())) {
            throw new IllegalStateException("Messaging Service returned an invalid message");
        }
        return message;
    }
}
