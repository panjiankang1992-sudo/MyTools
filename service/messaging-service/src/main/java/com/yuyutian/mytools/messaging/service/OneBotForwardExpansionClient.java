package com.yuyutian.mytools.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.messaging.config.MessagingProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 通过 OneBot Connector 读取合并转发内容。
 */
@Component
public class OneBotForwardExpansionClient {

    private final MessagingProperties properties;
    private final RestClient connectorClient;

    /**
     * 创建合并转发读取客户端。
     *
     * @param properties 消息服务配置
     * @param builder HTTP 客户端构建器
     */
    public OneBotForwardExpansionClient(MessagingProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.connectorClient = builder.clone().baseUrl(properties.providerResolverUrl()).build();
    }

    /**
     * 读取一个合并转发引用。
     *
     * @param accountKey OneBot 账户标识
     * @param forwardId 合并转发标识
     * @return 展开后的消息节点
     */
    public JsonNode expand(String accountKey, String forwardId) {
        if (properties.providerResolverToken() == null || properties.providerResolverToken().isBlank()) {
            throw new IllegalStateException("OneBot provider resolver token is unavailable");
        }
        JsonNode response = connectorClient.post().uri("/internal/v1/messages/forward/expand")
                .header("Authorization", "Bearer " + properties.providerResolverToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("accountKey", accountKey, "forwardId", forwardId))
                .retrieve().body(JsonNode.class);
        if (response == null || !response.path("messages").isContainerNode()) {
            throw new IllegalStateException("OneBot forward response is invalid");
        }
        return response.path("messages");
    }
}
