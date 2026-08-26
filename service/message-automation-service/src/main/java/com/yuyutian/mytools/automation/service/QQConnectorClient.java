package com.yuyutian.mytools.automation.service;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * QQ Connector 终态文本回执客户端。
 */
public class QQConnectorClient {

    private final RestClient restClient;
    private final String token;

    /**
     * 创建 QQ Connector 客户端。
     */
    public QQConnectorClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    /**
     * 被动回复原始 QQ 消息。
     */
    public void send(String sender, String messageId, String text, int sequence) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("QQ Connector token is missing");
        }
        restClient.post().uri("/internal/v1/messages/text")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sender", sender, "messageId", messageId, "text", text, "sequence", sequence))
                .retrieve().toBodilessEntity();
    }

    /**
     * 使用完成通知默认序号被动回复原始 QQ 消息。
     */
    public void send(String sender, String messageId, String text) {
        send(sender, messageId, text, 2);
    }
}
